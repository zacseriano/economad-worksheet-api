package zacseriano.economadworksheets.service.expense;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import zacseriano.economadworksheets.domain.dto.StatisticsDto;
import zacseriano.economadworksheets.domain.enums.StatisticsTypeEnum;
import zacseriano.economadworksheets.domain.form.ExpenseForm;
import zacseriano.economadworksheets.domain.mapper.ExpenseMapper;
import zacseriano.economadworksheets.domain.model.Expense;
import zacseriano.economadworksheets.domain.model.Origin;
import zacseriano.economadworksheets.domain.model.PaymentType;
import zacseriano.economadworksheets.repository.ExpenseRepository;
import zacseriano.economadworksheets.service.origin.OriginService;
import zacseriano.economadworksheets.service.paymentType.PaymentTypeService;
import zacseriano.economadworksheets.specification.builder.ExpenseSpecificationBuilder;
import zacseriano.economadworksheets.specification.filter.ExpenseFilter;

@Service
@Transactional
@RequiredArgsConstructor
public class ExpenseService {
	final static int RADIX = 10;
	@Autowired
	private ExpenseRepository expenseRepository;
	@Autowired
	private ExpenseMapper expenseMapper;
	@Autowired
	private OriginService originService;
	@Autowired
	private PaymentTypeService paymentTypeService;
	
	public Page<Expense> listAll(ExpenseFilter filter, Pageable paginacao) {		
		Specification<Expense> spec = ExpenseSpecificationBuilder.builder(filter);
		return expenseRepository.findAll(spec, paginacao);
	}
	
	public List<StatisticsDto> listStatisticsByMonth(String bilingMonth, StatisticsTypeEnum statisticsType) {
		LocalDate deadline = findDateByMonthDescription(bilingMonth);
		LocalDate initialDate = deadline.withDayOfMonth(1);
		LocalDate finalDate = deadline.withDayOfMonth(deadline.lengthOfMonth());
		ExpenseFilter filter = ExpenseFilter.builder().initialDate(initialDate).finalDate(finalDate).build();
		Specification<Expense> spec = ExpenseSpecificationBuilder.builder(filter);

		if(Expense.getSalary() == null) {
			throw new ValidationException(String.format("Please insert Month Salary information before moving forward."));
		}
		
		List<Expense> expenses = expenseRepository.findAll(spec);		
		Map<String, BigDecimal> mapExpenseStatistics = createExpensesStatisticsMap(expenses, statisticsType, Expense.getSalary());
		List<StatisticsDto> statistics = mapExpenseStatistics.entrySet().stream()
				.map(entry -> new StatisticsDto(entry.getKey(), entry.getValue())).collect(Collectors.toList());
		statistics.sort((e1, e2) -> e2.getTotal().compareTo(e1.getTotal()));
		return statistics;
	}

	public byte[] generateMonthlyWorksheet(String monthDescription) {
		ExpenseFilter filter = null;
		Specification<Expense> spec = ExpenseSpecificationBuilder.builder(filter);
		if(Expense.getSalary() == null) {
			throw new ValidationException(String.format("Please insert Month Salary information before moving forward."));
		}
		List<Expense> expenses = expenseRepository.findAll(spec);		
		return generateWorksheet(expenses);
	}
	
	public Expense create(ExpenseForm form) {
		Expense expense = expenseMapper.toModel(form);
		Origin origin = originService.findByNameOrCreate(form.getOriginName());
		expense.setOrigin(origin);
		PaymentType paymentType = paymentTypeService.findByName(form.getPaymentTypeName());
		expense.setPaymentType(paymentType);
		expense.setInstallment(createInstallment(form.getInstallmentNumber()));		
		if(form.getInstallmentNumber() > 1) {
			createRemainingExpenseInstallments(expense);
		}		
		expense = expenseRepository.save(expense);
		return expense;
	}
	
	public BigDecimal calculateRelativeDailyIndex(LocalDate initialDate, LocalDate finalDate) {
		ExpenseFilter filter = ExpenseFilter.builder().initialDate(initialDate).finalDate(finalDate).build();
		BigDecimal index = BigDecimal.ZERO;
		Specification<Expense> spec = ExpenseSpecificationBuilder.builder(filter);
		List<Expense> expenses = expenseRepository.findAll(spec);
		if(!expenses.isEmpty()) {
			BigDecimal daysNumber = new BigDecimal(ChronoUnit.DAYS.between(initialDate, finalDate));
			BigDecimal totalValue = expenses.stream().map(Expense::getExpenseValue).reduce(BigDecimal.ZERO, BigDecimal::add);
			index = totalValue.divide(daysNumber, 2, RoundingMode.CEILING);
		}		
		return index;
	}
	
	private LocalDate findDateByMonthDescription(String bilingMonth) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM/yyyy", Locale.ENGLISH);
        return LocalDate.parse(bilingMonth.toUpperCase(), formatter);
	}
	
	private String createInstallment(Integer installmentNumber) {
		StringBuilder sb = new StringBuilder();
	    sb.append("1/").append(installmentNumber);
		return sb.toString();
	}

	private Map<String, BigDecimal> createExpensesStatisticsMap(List<Expense> expenses, StatisticsTypeEnum statisticsType, BigDecimal salary) {		
		Map<String, BigDecimal> paymentTypeTotal = new HashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		if (statisticsType.equals(StatisticsTypeEnum.PAYMENT_TYPE)) {
			for (Expense expense : expenses) {
				String expensePaymentTypeName = expense.getPaymentType().getName();
				BigDecimal value = expense.getExpenseValue();
				total = total.add(value);
				if (paymentTypeTotal.containsKey(expensePaymentTypeName)) {
					value = value.add(paymentTypeTotal.get(expensePaymentTypeName));
				}
				paymentTypeTotal.put(expensePaymentTypeName, value);
			}
		}
		
		if (statisticsType.equals(StatisticsTypeEnum.ORIGIN)) {
			for (Expense expense : expenses) {
				String nomeOrigemDespesa = expense.getOrigin().getName();
				BigDecimal valor = expense.getExpenseValue();
				total = total.add(valor);
				if (paymentTypeTotal.containsKey(nomeOrigemDespesa)) {
					valor = valor.add(paymentTypeTotal.get(nomeOrigemDespesa));
				}
				paymentTypeTotal.put(nomeOrigemDespesa, valor);
			}
		}		
		paymentTypeTotal.put("TOTAL", total);
		paymentTypeTotal.put("Money left", salary.subtract(total));
		return paymentTypeTotal;
	}

	private String createNextInstallment(String parcela) {
		String[] parts = parcela.split("/");
		int installmentInt = Integer.parseInt(parts[0]);
		installmentInt++;
		String installmentNumber = Integer.toString(installmentInt);
		String newInstallment = installmentNumber + "/" + parts[1];
		return newInstallment;
	}
	
	private void createRemainingExpenseInstallments(Expense expense) {
		String installment = expense.getInstallment();
		String[] parts = installment.split("/");
		int quantity = Integer.parseInt(parts[1]);
		LocalDate deadline = expense.getDeadline();
		Expense actualExpense = expense;
		for(int i = quantity ; i > 1 ; i--) {
			Expense newExpense = new Expense();
			BeanUtils.copyProperties(actualExpense, newExpense, "id");
			installment = createNextInstallment(installment);
			newExpense.setInstallment(installment);
			deadline = deadline.plusMonths(1);
			newExpense.setDeadline(deadline);	
			actualExpense = newExpense;
			expenseRepository.save(newExpense);
		}				
	}
	
	private byte[] generateWorksheet(List<Expense> expenses) {
	    try (Workbook workbook = new XSSFWorkbook()) {
	        Sheet sheet = workbook.createSheet("Worksheet");

	        Row headerRow = sheet.createRow(0);
	        headerRow.createCell(0).setCellValue("Origin");
	        headerRow.createCell(1).setCellValue("Value");
	        headerRow.createCell(2).setCellValue("Date");
	        headerRow.createCell(4).setCellValue("Description");

	        int rowIndex = 1;
	        for (Expense expense : expenses) {
	            Row dataRow = sheet.createRow(rowIndex);
	            dataRow.createCell(0).setCellValue(expense.getOrigin().getName());
	            dataRow.createCell(1).setCellValue(Double.parseDouble(expense.getExpenseValue().toString()));
	            dataRow.createCell(2).setCellValue(expense.getDate().toString());
	            dataRow.createCell(4).setCellValue(expense.getDescription());
	            rowIndex++;
	        }

	        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
	            workbook.write(outputStream);
	            return outputStream.toByteArray();
	        }
	    } catch (IOException e) {
	        e.printStackTrace();
	        return null;
	    }
	}

}