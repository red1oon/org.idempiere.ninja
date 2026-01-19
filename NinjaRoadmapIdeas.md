# Ninja Roadmap Ideas - Logic Generator

**Author:** red1 - red1org@gmail.com
**Status:** Ideas for further study
**Goal:** Push the envelope - from model generator to killer logic generator

---

## Current State (v1.0)

Ninja currently generates:
- AD Models (Tables, Columns, Windows, Tabs, Fields, Menus)
- 2Pack.zip for distribution
- Complete OSGI plugin structure
- AD_Package_Exp for future changes

**What's missing:** Business logic. Developers still need to code processes, validators, callouts manually.

---

## Vision: Excel Defines WHAT, AI Generates HOW

```
┌─────────────────────────────────────────────────────────────┐
│                     Excel Workbook                          │
├─────────────┬─────────────┬─────────────┬─────────────────┤
│   Config    │    List     │    Model    │     Logic       │
│   Sheet     │    Sheet    │    Sheet    │     Sheets      │
├─────────────┴─────────────┴─────────────┴─────────────────┤
│                                                             │
│                    NINJA + AI Engine                        │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  Output:                                                    │
│  • AD Models (existing)                                     │
│  • 2Pack.zip (existing)                                     │
│  • Plugin structure (existing)                              │
│  • ModelValidator.java (NEW)                                │
│  • Callout.java (NEW)                                       │
│  • Process.java (NEW)                                       │
│  • EventHandler.java (NEW)                                  │
│  • plugin.xml with all registrations (NEW)                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Idea 1: Logic Sheet - Model Validators

### Excel Format
```
| Table       | Event      | Condition            | Action                           |
|-------------|------------|----------------------|----------------------------------|
| HR_Employee | BeforeSave | Salary < 0           | Error: "Salary must be positive" |
| HR_Employee | BeforeSave | HireDate > today     | Error: "Hire date cannot be future" |
| HR_Employee | AfterSave  | IsManager = Y        | Send email notification to HR    |
| C_Order     | BeforeSave | Lines.count = 0      | Error: "Order must have lines"   |
| C_Order     | OnComplete | GrandTotal > 10000   | Require approval workflow        |
| C_Invoice   | BeforeSave | *                    | Calculate tax from lines         |
| C_Invoice   | AfterSave  | IsPaid = Y           | Update customer balance          |
```

### Generated Output
```java
public class HR_EmployeeValidator implements ModelValidator {

    public String modelChange(PO po, int type) {
        MHREmployee emp = (MHREmployee) po;

        if (type == TYPE_BEFORE_NEW || type == TYPE_BEFORE_CHANGE) {
            // Rule: Salary must be positive
            if (emp.getSalary().compareTo(BigDecimal.ZERO) < 0) {
                return "Salary must be positive";
            }
            // Rule: Hire date cannot be future
            if (emp.getHireDate().after(new Date())) {
                return "Hire date cannot be future";
            }
        }

        if (type == TYPE_AFTER_NEW || type == TYPE_AFTER_CHANGE) {
            // Rule: Notify HR for new managers
            if (emp.isManager()) {
                sendEmailToHR(emp);
            }
        }

        return null;
    }
}
```

### Event Types to Support
- `BeforeNew` - Before record creation
- `AfterNew` - After record creation
- `BeforeSave` - Before any save (new or change)
- `AfterSave` - After any save
- `BeforeDelete` - Before record deletion
- `AfterDelete` - After record deletion
- `OnComplete` - Document completion
- `OnVoid` - Document void
- `OnClose` - Document close
- `OnReactivate` - Document reactivate

---

## Idea 2: Callout Sheet

### Excel Format
```
| Table       | Field         | Trigger    | Action                                |
|-------------|---------------|------------|---------------------------------------|
| C_Order     | C_BPartner_ID | OnChange   | Copy BillTo and ShipTo addresses      |
| C_Order     | M_Product_ID  | OnChange   | Set price from pricelist              |
| C_Order     | M_Product_ID  | OnChange   | Check available stock, warn if low    |
| C_Order     | Qty           | OnChange   | Recalculate line amount               |
| C_Order     | C_Currency_ID | OnChange   | Convert all line prices               |
| HR_Employee | Department_ID | OnChange   | Set default manager from department   |
| HR_Employee | JobTitle      | OnChange   | Set default salary range              |
```

### Generated Output
```java
public class OrderCallout implements IColumnCallout {

    public String start(Properties ctx, int WindowNo, GridTab mTab,
                        GridField mField, Object value, Object oldValue) {

        String column = mField.getColumnName();

        if ("C_BPartner_ID".equals(column)) {
            // Copy BillTo and ShipTo addresses
            int bpartnerId = (Integer) value;
            MBPartner bp = MBPartner.get(ctx, bpartnerId);
            MBPartnerLocation[] locations = bp.getLocations(false);
            // ... set addresses
        }

        if ("M_Product_ID".equals(column)) {
            // Set price from pricelist
            int productId = (Integer) value;
            BigDecimal price = getPriceFromPriceList(ctx, productId, ...);
            mTab.setValue("PriceEntered", price);

            // Check stock
            BigDecimal qtyAvailable = getQtyAvailable(productId);
            if (qtyAvailable.compareTo(BigDecimal.ZERO) <= 0) {
                return "Warning: Product out of stock";
            }
        }

        return null;
    }
}
```

---

## Idea 3: Process Sheet

### Excel Format
```
| Name            | Description                  | Parameters                      |
|-----------------|------------------------------|---------------------------------|
| ApproveOrders   | Bulk approve pending orders  | DateFrom:Date, DateTo:Date, Status:List |
| SendReminders   | Email overdue invoice reminders | DaysBefore:Integer, Template:String |
| ImportProducts  | Import products from CSV     | FilePath:String, UpdateExisting:YesNo |
| MonthlyPayroll  | Process monthly payroll      | Month:Date, Department:TableDir |
| RecalculateTax  | Recalculate tax for invoices | DateFrom:Date, DateTo:Date      |
```

### Process Logic (Natural Language)
```
| Name          | Logic                                                      |
|---------------|-------------------------------------------------------------|
| ApproveOrders | For each C_Order where DocStatus='DR' and DateOrdered      |
|               | between DateFrom and DateTo: set DocStatus='CO', save      |
| SendReminders | For each C_Invoice where IsPaid='N' and DueDate <          |
|               | today-DaysBefore: send email using Template                |
| ImportProducts| Read CSV from FilePath, for each row: find or create       |
|               | M_Product, set values, save                                |
```

### Generated Output
```java
public class ApproveOrdersProcess extends SvrProcess {

    // Parameters
    private Timestamp p_DateFrom;
    private Timestamp p_DateTo;
    private String p_Status;

    @Override
    protected void prepare() {
        for (ProcessInfoParameter para : getParameter()) {
            if ("DateFrom".equals(para.getParameterName()))
                p_DateFrom = para.getParameterAsTimestamp();
            else if ("DateTo".equals(para.getParameterName()))
                p_DateTo = para.getParameterAsTimestamp();
            else if ("Status".equals(para.getParameterName()))
                p_Status = para.getParameterAsString();
        }
    }

    @Override
    protected String doIt() throws Exception {
        String sql = "SELECT * FROM C_Order WHERE DocStatus=? " +
                     "AND DateOrdered BETWEEN ? AND ?";
        // ... process orders
        return "Approved " + count + " orders";
    }
}
```

---

## Idea 4: AI-Assisted Natural Language Logic

### Excel Format - Free Text Description
```
| Process       | Description                                                   |
|---------------|---------------------------------------------------------------|
| MonthlyPayroll| For each active employee in the selected department:          |
|               | 1. Get attendance records for the month                       |
|               | 2. Calculate base salary pro-rated by attendance              |
|               | 3. Add overtime hours * 1.5 rate                              |
|               | 4. Deduct tax based on tax bracket                            |
|               | 5. Deduct insurance and pension contributions                 |
|               | 6. Create HR_PayrollLine record                               |
|               | 7. If employee has direct deposit, create payment             |
|               | 8. Send payslip email with PDF attachment                     |
```

### AI Processing
1. Parse natural language description
2. Identify entities (Employee, Attendance, PayrollLine, etc.)
3. Identify operations (calculate, deduct, create, send)
4. Generate Java code with proper iDempiere patterns
5. Include error handling and logging

### Generated Output
AI generates complete, working Java code following iDempiere conventions.

---

## Idea 5: Rule Engine Sheet

### Excel Format
```
| Rule Name     | Entity    | Condition                        | Action                    | Priority |
|---------------|-----------|----------------------------------|---------------------------|----------|
| AutoDiscount  | C_Order   | BPartner.TotalYTD > 100000       | Set Discount = 5%         | 10       |
| VIPDiscount   | C_Order   | BPartner.IsVIP = Y               | Set Discount = 10%        | 20       |
| CreditHold    | C_Order   | BPartner.OpenBalance > CreditLimit| Block order, notify sales | 30       |
| AutoReorder   | M_Product | QtyOnHand < ReorderLevel         | Create purchase order     | 10       |
| LowStock      | M_Product | QtyOnHand < SafetyStock          | Send alert to warehouse   | 20       |
| ExpiringSoon  | M_Product | GuaranteeDate < today + 30       | Mark for clearance sale   | 10       |
```

### Implementation Options

**Option A: Generate Static Validators**
- Convert rules to Java code at generation time
- Fast execution, but requires regeneration for rule changes

**Option B: Runtime Rule Engine**
- Store rules in database tables
- Evaluate at runtime using expression parser
- Flexible, rules can change without code deployment

**Option C: Drools Integration**
- Generate Drools DRL files from Excel
- Full rule engine capabilities
- Complex rule interactions supported

---

## Idea 6: Form Generator

### Excel Format - Form Layout
```
| Row | Col1              | Col2            | Col3          |
|-----|-------------------|-----------------|---------------|
| 1   | [Label:Customer]  | [Field:C_BPartner_ID:span2]     |
| 2   | [Label:Address]   | [Field:Address1]| [Field:City]  |
| 3   | [Label:Product]   | [Field:M_Product_ID]| [Field:Qty]|
| 4   | [Button:Calculate]| [Button:Save]   | [Button:Cancel]|
```

### Generated Output
- ZK ZUL file with layout
- Java controller class
- Registered in plugin.xml

---

## Idea 7: Report Generator

### Excel Format
```
| Report Name    | Base Table | Columns                          | Group By   | Filter           |
|----------------|------------|----------------------------------|------------|------------------|
| SalesByProduct | C_OrderLine| Product, Qty, Amount, OrderDate  | Product    | DateOrdered      |
| EmployeeList   | HR_Employee| Name, Department, Salary, HireDate| Department| IsActive         |
| OpenInvoices   | C_Invoice  | BPartner, DocumentNo, GrandTotal | BPartner   | IsPaid='N'       |
```

### Generated Output
- JasperReport JRXML file
- AD_Process registration
- AD_PrintFormat (optional)

---

## Idea 8: REST API Generator

### Excel Format
```
| Endpoint       | Method | Table       | Operations | Auth      |
|----------------|--------|-------------|------------|-----------|
| /api/products  | GET    | M_Product   | list,get   | API Key   |
| /api/orders    | ALL    | C_Order     | CRUD       | OAuth     |
| /api/customers | GET,PUT| C_BPartner  | get,update | API Key   |
```

### Generated Output
- REST controller classes
- OpenAPI/Swagger documentation
- Authentication filters

---

## Idea 9: Import/Export Generator

### Excel Format
```
| Name           | Table       | Format | Columns                    | Key Column |
|----------------|-------------|--------|----------------------------|------------|
| ProductImport  | M_Product   | CSV    | Value,Name,Price,Category  | Value      |
| CustomerImport | C_BPartner  | XLSX   | Name,TaxID,Address,City    | TaxID      |
| OrderExport    | C_Order     | JSON   | DocumentNo,Date,Lines      | -          |
```

### Generated Output
- AD_ImpFormat records
- Import process
- Export process

---

## Idea 10: Dashboard/KPI Generator

### Excel Format
```
| KPI Name       | Query                                    | Type    | Refresh  |
|----------------|------------------------------------------|---------|----------|
| TodaySales     | SELECT SUM(GrandTotal) FROM C_Order ...  | Number  | 5 min    |
| OpenOrders     | SELECT COUNT(*) FROM C_Order ...         | Number  | 5 min    |
| SalesTrend     | SELECT Date, SUM(Amount) ... GROUP BY    | Chart   | 1 hour   |
| TopProducts    | SELECT Product, SUM(Qty) ... TOP 10      | Table   | 1 hour   |
```

### Generated Output
- Dashboard panel classes
- ZK components
- Auto-refresh logic

---

## Implementation Priority

### Phase 1: Core Logic (High Impact)
1. **Logic Sheet** - Model Validators (most common need)
2. **Callout Sheet** - Field-level logic
3. **Process Sheet** - Background processes

### Phase 2: Advanced Logic
4. **Rule Engine** - Dynamic business rules
5. **AI Natural Language** - Complex logic generation

### Phase 3: UI & Integration
6. **Form Generator** - Custom forms
7. **Report Generator** - JasperReports
8. **REST API Generator** - External integration

### Phase 4: Analytics
9. **Dashboard Generator** - KPIs and charts
10. **Import/Export Generator** - Data exchange

---

## Technical Considerations

### Code Generation Templates
- Use FreeMarker or Velocity for Java code templates
- Maintain iDempiere coding conventions
- Include proper imports, error handling, logging

### AI Integration
- OpenAI API for natural language processing
- Fine-tune on iDempiere codebase for better output
- Human review step before final generation

### Validation
- Syntax validation of generated code
- Compile-time checking
- Unit test generation (bonus)

### Versioning
- Track changes to logic sheets
- Generate migration 2Packs for logic changes
- Support incremental updates

---

## Success Criteria

A "killer" logic generator should:

1. **Reduce development time by 80%** for standard business logic
2. **Require zero Java knowledge** for simple rules
3. **Generate production-quality code** that follows iDempiere best practices
4. **Be extensible** for complex custom logic
5. **Maintain full audit trail** of generated vs custom code

---

## Next Steps

1. Prototype Logic Sheet (Model Validators) - highest impact
2. Test with real-world scenarios (HR, Sales, Inventory)
3. Gather feedback from iDempiere community
4. Iterate and expand to other sheets

---

*"The best code is the code you don't have to write"*

**Ninja - Making iDempiere development accessible to everyone**
