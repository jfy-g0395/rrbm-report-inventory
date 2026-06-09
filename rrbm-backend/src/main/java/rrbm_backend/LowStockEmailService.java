package rrbm_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sends branded HTML email alerts when products hit low-stock thresholds.
 * Emails are sent asynchronously so they don't block order processing.
 */
@Service
public class LowStockEmailService {

    private final JavaMailSender mailSender;
    private final NotificationEmailRepository emailRepo;
    private final ActivityLogService activityLogService;

    @Value("${rrbm.mail.from:noreply@rrbm.com}")
    private String fromAddress;

    @Value("${rrbm.mail.enabled:false}")
    private boolean mailEnabled;

    public LowStockEmailService(JavaMailSender mailSender,
                                 NotificationEmailRepository emailRepo,
                                 ActivityLogService activityLogService) {
        this.mailSender         = mailSender;
        this.emailRepo          = emailRepo;
        this.activityLogService = activityLogService;
    }

    /**
     * Send a low-stock alert email to all configured recipients.
     * Called asynchronously after stock deduction detects low items.
     */
    @Async
    public void sendLowStockAlert(List<Product> lowProducts) {
        if (!mailEnabled) return;
        if (lowProducts == null || lowProducts.isEmpty()) return;

        List<NotificationEmail> recipients = emailRepo.findAll();
        if (recipients.isEmpty()) return;

        String subject = "RRBM Low Stock Alert — " + lowProducts.size() + " item(s) below threshold";
        String htmlBody = buildEmailHtml(lowProducts);

        List<String> emails = recipients.stream()
                .map(NotificationEmail::getEmail)
                .collect(Collectors.toList());

        for (String toEmail : emails) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(htmlBody, true);
                mailSender.send(msg);
            } catch (Exception e) {
                // Log failure but don't crash — email is non-critical
                System.err.println("Failed to send low-stock email to " + toEmail + ": " + e.getMessage());
            }
        }

        // Log the alert in activity log
        String productNames = lowProducts.stream()
                .map(Product::getName)
                .limit(5)
                .collect(Collectors.joining(", "));
        if (lowProducts.size() > 5) productNames += " +" + (lowProducts.size() - 5) + " more";

        activityLogService.log(null, "System", "LOW_STOCK_ALERT",
                "Low stock email sent to " + emails.size() + " recipient(s). Items: " + productNames,
                "INVENTORY", "low_stock");
    }

    private String buildEmailHtml(List<Product> products) {
        StringBuilder rows = new StringBuilder();
        for (Product p : products) {
            int total = p.getTotalStock();
            String tagColor = "HOT".equalsIgnoreCase(p.getSellingTag()) ? "#EF4444"
                            : "SELLING".equalsIgnoreCase(p.getSellingTag()) ? "#F59E0B" : "#6B7280";
            rows.append("<tr>")
                .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;font-size:13px;'>")
                .append(p.getName()).append("</td>")
                .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;text-align:center;'>")
                .append("<span style='background:").append(tagColor)
                .append(";color:#fff;padding:2px 8px;border-radius:10px;font-size:10px;font-weight:600;'>")
                .append(p.getSellingTag() != null ? p.getSellingTag() : "—").append("</span></td>")
                .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;text-align:right;font-weight:700;")
                .append(total <= (p.getThresholdCritical() != null ? p.getThresholdCritical() : 0)
                        ? "color:#EF4444;" : "color:#F59E0B;")
                .append("'>").append(String.format("%,d", total)).append(" pcs</td>")
                .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;text-align:right;font-size:12px;color:#888;'>")
                .append("WH1: ").append(p.getStockWh1() != null ? p.getStockWh1() : 0)
                .append(" | WH2: ").append(p.getStockWh2() != null ? p.getStockWh2() : 0)
                .append(" | Balagtas: ").append(p.getStockWh3() != null ? p.getStockWh3() : 0)
                .append("</td></tr>");
        }

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='font-family:Arial,sans-serif;background:#FAF7F2;margin:0;padding:0;'>"
            + "<div style='max-width:640px;margin:20px auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08);'>"
            // Header
            + "<div style='background:#2C1A0E;padding:20px 24px;text-align:center;'>"
            + "<div style='font-size:22px;font-weight:700;color:#D4860A;letter-spacing:1px;'>RRBM</div>"
            + "<div style='font-size:11px;color:rgba(255,255,255,0.6);margin-top:4px;'>Packaging Supplies and Trading</div>"
            + "</div>"
            // Alert banner
            + "<div style='background:#FEF2F2;border-bottom:1px solid #FECACA;padding:14px 24px;'>"
            + "<div style='font-size:14px;font-weight:700;color:#991B1B;'>&#9888; Low Stock Alert</div>"
            + "<div style='font-size:12px;color:#7F1D1D;margin-top:4px;'>"
            + products.size() + " product(s) are below their stock threshold and need attention.</div>"
            + "</div>"
            // Table
            + "<div style='padding:16px 24px;'>"
            + "<table style='width:100%;border-collapse:collapse;'>"
            + "<thead><tr style='background:#F5F0E8;'>"
            + "<th style='padding:8px 12px;text-align:left;font-size:11px;font-weight:600;color:#6B5C42;'>Product</th>"
            + "<th style='padding:8px 12px;text-align:center;font-size:11px;font-weight:600;color:#6B5C42;'>Tag</th>"
            + "<th style='padding:8px 12px;text-align:right;font-size:11px;font-weight:600;color:#6B5C42;'>Total Stock</th>"
            + "<th style='padding:8px 12px;text-align:right;font-size:11px;font-weight:600;color:#6B5C42;'>Warehouse</th>"
            + "</tr></thead><tbody>"
            + rows.toString()
            + "</tbody></table>"
            + "</div>"
            // Footer
            + "<div style='background:#F5F0E8;padding:14px 24px;font-size:11px;color:#9C8B70;text-align:center;'>"
            + "This is an automated notification from your RRBM system.<br>"
            + "Please restock the listed items at your earliest convenience."
            + "</div>"
            + "</div></body></html>";
    }
}
