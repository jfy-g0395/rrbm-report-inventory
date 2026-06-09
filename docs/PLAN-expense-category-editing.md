# Plan: Expense Category Editing in Review Modal

**Session:** U19 Round 6 (planned)
**Date:** Jun 9, 2026
**Status:** IMPLEMENTED

## Goal
Allow users to edit the category of FUZZY-matched expenses in the review modal, so they can correct mis-categorized items before committing.

## Context
During expense import, the backend resolves categories via:
- **EXACT match:** CSV category code matches a database code → high confidence
- **FUZZY match:** Category inferred from 27 hardcoded keywords → lower confidence, might be wrong

Currently, the review modal shows category as static text — users cannot change it. If the system inferred the wrong category, the only option is to exclude the expense.

## Design Decisions
- **FUZZY items:** Show cascading dropdown (Primary → Sub-category) — user can change category
- **EXACT items:** Stay read-only — system is confident
- **NO MATCH items:** Still go to `needsFix` — no change for now (Note: backend always sends EXACT or FUZZY for expenses — NO MATCH is unreachable)
- **Cascading pattern:** Matches existing expense form (`exp-primary-cat` → `exp-sub-cat`)
- **Green gate:** FUZZY items start green (they have a `categoryId` from the keyword match). Changing sub-category updates the override and preserves green. Clearing sub-category removes green.

## Backend
- **No changes needed** — `GET /api/expense-categories` already returns hierarchical data
- **Commit endpoint** already accepts `categoryId` override in expense payload (`ImportController.java:2091-2092`)

## Frontend Changes

### 1. Lazy-load categories on modal open
**File:** `js/app.js` — `openReviewModal()` (~line 11335)

Wrapped rendering in `loadExpenseCategories().then(...)` to ensure `_expCatData` is populated before rendering cards. Categories are cached after first load.

### 2. Render category dropdown for FUZZY items
**File:** `js/app.js` — `_renderReviewCard()` expense branch (~line 11455)

Replaced static category text with cascading `<select>` when `item.matchConfidence === 'FUZZY'`:

```javascript
if (item.matchConfidence === 'FUZZY' && _expCatData) {
  var catPath = _findCatPath(item.categoryId);
  var key = esc(item.referenceNumber || item.date + '_' + item.amount);
  catHtml = '<div class="rc-cat-edit">'
    + '<select class="form-control form-select review-editor-input" data-field="exp-cat-primary" data-key="' + key + '">'
    + _expCatPrimaryOpts(catPath.primaryCode) + '</select>'
    + '<select class="form-control form-select review-editor-input" data-field="exp-cat-sub" data-key="' + key + '">'
    + _expCatSubOpts(catPath.primaryCode, catPath.subId) + '</select>'
    + '</div>';
} else {
  catHtml = '<span>' + esc(item.category || 'Uncategorized') + '...</span>';
}
```

### 3. Helper functions for category dropdowns
**File:** `js/app.js` — new functions near `_paymentOpts()` (~line 11642)

```javascript
function _expCatPrimaryOpts(selectedCode) { ... }  // Primary category <select> options
function _expCatSubOpts(primaryCode, selectedSubId) { ... }  // Sub-category <select> options
function _findCatPath(categoryId) { ... }  // Reverse-lookup: sub-category ID → { primaryCode, subId }
```

`_findCatPath` searches `_expCatData.primaries[].subcategories[]` to find which primary a given sub-category ID belongs to. Required because the backend response includes `categoryId` (sub-category ID) but not the primary code.

### 4. Event listeners for category dropdowns
**File:** `js/app.js` — `_setupReviewCardEvents()` (~line 11766)

```javascript
// Primary category change → populate sub-category, clear override
container.querySelectorAll('select[data-field="exp-cat-primary"]').forEach(function (sel) {
  sel.addEventListener('change', function () {
    var key = this.getAttribute('data-key');
    var subSel = this.parentElement.querySelector('select[data-field="exp-cat-sub"]');
    if (subSel) { subSel.innerHTML = _expCatSubOpts(this.value, ''); }
    _setExpenseField(key, 'categoryId', null);
    _reviewGreenItems.forEach(function (gi) {
      if (gi._key === key) gi._green = false;
    });
    _recalcGreenGate();
  });
});

// Sub-category change → store categoryId override
container.querySelectorAll('select[data-field="exp-cat-sub"]').forEach(function (sel) {
  sel.addEventListener('change', function () {
    var key = this.getAttribute('data-key');
    var subId = this.value ? parseInt(this.value) : null;
    _setExpenseField(key, 'categoryId', subId);
    _reviewGreenItems.forEach(function (gi) {
      if (gi._key === key) gi._green = subId != null;
    });
    _recalcGreenGate();
  });
});
```

### 5. CSS styling
**File:** `css/styles.css` — after `.rc-items-scroll`

```css
.rc-cat-edit {
  display: flex;
  gap: 4px;
  flex: 1;
}
.rc-cat-edit select {
  font-size: 10px;
  padding: 2px 4px;
  flex: 1;
}
```

## Files Touched
| File | Changes |
|------|---------|
| `js/app.js` | ~65 lines: lazy-load, 3 helpers, render dropdown, event listeners |
| `css/styles.css` | ~10 lines: `.rc-cat-edit` styling |

## Verification
- `node --check app.js` — no syntax errors
- Manual test: upload expense CSV with fuzzy categories → verify dropdown works → change category → commit → verify correct categoryId saved

## Not in Scope
- NO MATCH items (stay in needsFix)
- EXACT items (stay read-only)
- Backend changes (none needed)
