package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reference-data endpoint for expense categories.
 *
 *  GET /api/expense-categories — Returns active primary categories with their
 *                                 sub-categories, ordered by sort_order.
 *                                 No authentication required.
 */
@RestController
@RequestMapping("/api/expense-categories")
@CrossOrigin(origins = "*")
public class ExpenseCategoryController {

    private final ExpenseCategoryRepository categoryRepository;

    public ExpenseCategoryController(ExpenseCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getCategories() {
        List<Map<String, Object>> primaryList = categoryRepository
                .findByParentIdIsNullOrderBySortOrderAscNameAsc()
                .stream()
                .filter(ExpenseCategory::isActive)
                .map(p -> {
                    List<Map<String, Object>> subList = categoryRepository
                            .findByParentIdOrderBySortOrderAscNameAsc(p.getId())
                            .stream()
                            .filter(ExpenseCategory::isActive)
                            .map(s -> {
                                Map<String, Object> sm = new LinkedHashMap<>();
                                sm.put("id",              s.getId());
                                sm.put("name",            s.getName());
                                sm.put("sortOrder",       s.getSortOrder());
                                sm.put("requiresReceipt", s.isRequiresReceipt());
                                return (Map<String, Object>) sm;
                            })
                            .collect(Collectors.toList());

                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id",            p.getId());
                    pm.put("code",          p.getCode());
                    pm.put("name",          p.getName());
                    pm.put("sortOrder",     p.getSortOrder());
                    pm.put("subcategories", subList);
                    return pm;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("primaries", primaryList));
    }
}
