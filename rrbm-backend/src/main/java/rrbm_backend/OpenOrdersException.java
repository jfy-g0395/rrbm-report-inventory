package rrbm_backend;

import java.math.BigDecimal;

/**
 * Thrown by DailyReportService when daily close is blocked because ACTIVE/PENDING
 * orders still exist.  Carries the count and total so the controller can return a
 * structured 409 response that the frontend uses to show the force-close override modal.
 */
public class OpenOrdersException extends RuntimeException {

    private final int count;
    private final BigDecimal amount;

    public OpenOrdersException(int count, BigDecimal amount) {
        super("Cannot close daily sales: " + count + " order(s) are still ACTIVE or PENDING.");
        this.count  = count;
        this.amount = amount;
    }

    public int getCount()           { return count; }
    public BigDecimal getAmount()   { return amount; }
}
