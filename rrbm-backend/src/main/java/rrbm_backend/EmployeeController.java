package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Employee 201 records (HR). Personnel data, separate from {@code /api/users} (system logins).
 *
 *  POST  /api/employees                — register (Personal Info required; children optional)
 *  GET   /api/employees?status=&q=     — list
 *  GET   /api/employees/{id}           — full 201 (+ education, work history, benefits, events, age)
 *  PUT   /api/employees/{id}           — update (+ auto milestone events on wage/position/status change)
 *  PATCH /api/employees/{id}/status    — ACTIVE/RESIGNED/TERMINATED
 *  POST  /api/employees/{id}/events    — add MEMO/ADDENDUM/NOTE
 *  GET   /api/employees/benefit-types  — catalog · POST/PATCH (admin) to manage it
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeRepository            employeeRepository;
    private final EmployeeEducationRepository   educationRepository;
    private final EmployeeWorkHistoryRepository workHistoryRepository;
    private final BenefitTypeRepository         benefitTypeRepository;
    private final EmployeeBenefitRepository     benefitRepository;
    private final EmployeeEventRepository       eventRepository;
    private final UserRepository                userRepository;
    private final JwtUtil                        jwtUtil;

    public EmployeeController(EmployeeRepository employeeRepository,
                              EmployeeEducationRepository educationRepository,
                              EmployeeWorkHistoryRepository workHistoryRepository,
                              BenefitTypeRepository benefitTypeRepository,
                              EmployeeBenefitRepository benefitRepository,
                              EmployeeEventRepository eventRepository,
                              UserRepository userRepository,
                              JwtUtil jwtUtil) {
        this.employeeRepository    = employeeRepository;
        this.educationRepository   = educationRepository;
        this.workHistoryRepository = workHistoryRepository;
        this.benefitTypeRepository = benefitTypeRepository;
        this.benefitRepository     = benefitRepository;
        this.eventRepository       = eventRepository;
        this.userRepository        = userRepository;
        this.jwtUtil               = jwtUtil;
    }

    // ── POST /api/employees ────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String lastName  = trim(body, "lastName");
        String firstName = trim(body, "firstName");
        String position  = trim(body, "position");
        LocalDate birthdate = parseDate(body, "birthdate");
        LocalDate doe       = parseDate(body, "dateOfEmployment");
        String contactNumber = trim(body, "contactNumber");

        if (isBlank(lastName))      return ResponseEntity.badRequest().body(Map.of("error", "lastName is required"));
        if (isBlank(firstName))     return ResponseEntity.badRequest().body(Map.of("error", "firstName is required"));
        if (isBlank(position))      return ResponseEntity.badRequest().body(Map.of("error", "position is required"));
        if (birthdate == null)      return ResponseEntity.badRequest().body(Map.of("error", "birthdate is required"));
        if (doe == null)            return ResponseEntity.badRequest().body(Map.of("error", "dateOfEmployment is required"));
        if (isBlank(contactNumber)) return ResponseEntity.badRequest().body(Map.of("error", "contactNumber is required"));

        Employee e = new Employee();
        applyPersonalFields(e, body);
        int year = LocalDate.now().getYear();
        int seq  = employeeRepository.maxSequenceForPrefix("EMP-" + year + "-%") + 1;
        e.setEmployeeCode(String.format("EMP-%d-%04d", year, seq));
        e.setCreatedBy(adminId);
        Employee saved = employeeRepository.save(e);

        replaceChildren(saved.getId(), body);
        return ResponseEntity.status(201).body(fullDetail(saved));
    }

    // ── GET /api/employees?status=&q= ──────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "status", defaultValue = "ALL") String status,
            @RequestParam(value = "q",      required = false) String q) {

        List<Employee> employees = "ALL".equalsIgnoreCase(status)
                ? employeeRepository.findAllByOrderByLastNameAscFirstNameAsc()
                : employeeRepository.findByStatusOrderByLastNameAscFirstNameAsc(status.toUpperCase());

        if (q != null && !q.isBlank()) {
            final String needle = q.trim().toLowerCase();
            employees = employees.stream().filter(e ->
                    (fullName(e).toLowerCase().contains(needle))
                 || (e.getPosition() != null && e.getPosition().toLowerCase().contains(needle))
                 || (e.getEmployeeCode() != null && e.getEmployeeCode().toLowerCase().contains(needle)))
                .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = employees.stream().map(this::summary).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── GET /api/employees/{id} ────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Employee e = employeeRepository.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(Map.of("error", "Employee not found"));
        return ResponseEntity.ok(fullDetail(e));
    }

    // ── PUT /api/employees/{id} — update + auto milestone events ────────────

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Employee e = employeeRepository.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(Map.of("error", "Employee not found"));

        // Capture pre-update values for milestone diffing.
        BigDecimal oldWage   = e.getDailyWage();
        String     oldPos    = e.getPosition();
        String     oldStatus = e.getEmploymentStatus();

        applyPersonalFields(e, body);
        Employee saved = employeeRepository.save(e);

        // Auto-append milestone events for the tracked changes.
        if (body.containsKey("dailyWage") && !java.util.Objects.equals(oldWage, saved.getDailyWage()))
            addEvent(id, "SALARY_CHANGE", moneyStr(oldWage), moneyStr(saved.getDailyWage()), null, adminId);
        if (body.containsKey("position") && !java.util.Objects.equals(oldPos, saved.getPosition()))
            addEvent(id, "POSITION_CHANGE", oldPos, saved.getPosition(), null, adminId);
        if (body.containsKey("employmentStatus") && !java.util.Objects.equals(oldStatus, saved.getEmploymentStatus()))
            addEvent(id, "STATUS_CHANGE", oldStatus, saved.getEmploymentStatus(), null, adminId);

        if (body.containsKey("education") || body.containsKey("workHistory") || body.containsKey("benefits"))
            replaceChildren(id, body);

        return ResponseEntity.ok(fullDetail(saved));
    }

    // ── PATCH /api/employees/{id}/status ──────────────────────────────────

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        Employee e = employeeRepository.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(Map.of("error", "Employee not found"));

        String newStatus = upper(trim(body, "status"));
        if (!List.of("ACTIVE", "RESIGNED", "TERMINATED").contains(newStatus))
            return ResponseEntity.badRequest().body(Map.of("error", "status must be ACTIVE, RESIGNED or TERMINATED"));
        e.setStatus(newStatus);
        return ResponseEntity.ok(fullDetail(employeeRepository.save(e)));
    }

    // ── POST /api/employees/{id}/events — memo / addendum / note ───────────

    @PostMapping("/{id}/events")
    @Transactional
    public ResponseEntity<?> addManualEvent(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(Map.of("error", "Employee not found"));

        String type = upper(trim(body, "eventType"));
        if (!List.of("MEMO", "ADDENDUM", "NOTE").contains(type))
            return ResponseEntity.badRequest().body(Map.of("error", "eventType must be MEMO, ADDENDUM or NOTE"));
        String details = trim(body, "details");
        if (isBlank(details)) return ResponseEntity.badRequest().body(Map.of("error", "details is required"));

        EmployeeEvent ev = new EmployeeEvent();
        ev.setEmployeeId(id);
        ev.setEventType(type);
        LocalDate d = parseDate(body, "eventDate");
        if (d != null) ev.setEventDate(d);
        ev.setDetails(details);
        ev.setCreatedBy(adminId);
        eventRepository.save(ev);
        return ResponseEntity.status(201).body(eventMap(ev));
    }

    // ── Benefit types catalog ──────────────────────────────────────────────

    @GetMapping("/benefit-types")
    public ResponseEntity<?> benefitTypes() {
        return ResponseEntity.ok(benefitTypeRepository.findByActiveTrueOrderByName().stream()
                .map(this::benefitTypeMap).collect(Collectors.toList()));
    }

    @PostMapping("/benefit-types")
    @Transactional
    public ResponseEntity<?> addBenefitType(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {
        if (!isAdmin(authHeader)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String name = trim(body, "name");
        if (isBlank(name)) return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        if (benefitTypeRepository.findByNameIgnoreCase(name).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "Benefit type already exists"));
        BenefitType bt = new BenefitType();
        bt.setName(name);
        bt.setIsGovernment(Boolean.TRUE.equals(body.get("isGovernment")));
        return ResponseEntity.status(201).body(benefitTypeMap(benefitTypeRepository.save(bt)));
    }

    @PatchMapping("/benefit-types/{id}")
    @Transactional
    public ResponseEntity<?> updateBenefitType(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        if (!isAdmin(authHeader)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        BenefitType bt = benefitTypeRepository.findById(id).orElse(null);
        if (bt == null) return ResponseEntity.status(404).body(Map.of("error", "Benefit type not found"));
        if (body.containsKey("active")) bt.setActive(Boolean.TRUE.equals(body.get("active")));
        if (body.containsKey("name") && !isBlank(trim(body, "name"))) bt.setName(trim(body, "name"));
        return ResponseEntity.ok(benefitTypeMap(benefitTypeRepository.save(bt)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void applyPersonalFields(Employee e, Map<String, Object> b) {
        if (b.containsKey("lastName"))         e.setLastName(trim(b, "lastName"));
        if (b.containsKey("firstName"))        e.setFirstName(trim(b, "firstName"));
        if (b.containsKey("middleName"))       e.setMiddleName(trim(b, "middleName"));
        if (b.containsKey("maidenName"))       e.setMaidenName(trim(b, "maidenName"));
        if (b.containsKey("birthdate"))        e.setBirthdate(parseDate(b, "birthdate"));
        if (b.containsKey("nationality"))      e.setNationality(trim(b, "nationality"));
        if (b.containsKey("civilStatus"))      e.setCivilStatus(trim(b, "civilStatus"));
        if (b.containsKey("gender"))           e.setGender(trim(b, "gender"));
        if (b.containsKey("position"))         e.setPosition(trim(b, "position"));
        if (b.containsKey("dateOfEmployment")) e.setDateOfEmployment(parseDate(b, "dateOfEmployment"));
        if (b.containsKey("email"))            e.setEmail(trim(b, "email"));
        if (b.containsKey("spouseName"))       e.setSpouseName(trim(b, "spouseName"));
        if (b.containsKey("contactNumber"))    e.setContactNumber(trim(b, "contactNumber"));
        if (b.containsKey("address"))          e.setAddress(trim(b, "address"));
        if (b.containsKey("sssNumber"))        e.setSssNumber(trim(b, "sssNumber"));
        if (b.containsKey("pagibigNumber"))    e.setPagibigNumber(trim(b, "pagibigNumber"));
        if (b.containsKey("philhealthNumber")) e.setPhilhealthNumber(trim(b, "philhealthNumber"));
        if (b.containsKey("photo"))            e.setPhoto(trim(b, "photo"));
        if (b.containsKey("employmentStatus") && !isBlank(trim(b, "employmentStatus")))
                                               e.setEmploymentStatus(upper(trim(b, "employmentStatus")));
        if (b.containsKey("probationEndDate")) e.setProbationEndDate(parseDate(b, "probationEndDate"));
        if (b.containsKey("dailyWage"))        e.setDailyWage(parseDecimal(b.get("dailyWage")));
        if (b.containsKey("status") && !isBlank(trim(b, "status")))
                                               e.setStatus(upper(trim(b, "status")));
    }

    @SuppressWarnings("unchecked")
    private void replaceChildren(Long empId, Map<String, Object> b) {
        if (b.containsKey("education")) {
            educationRepository.deleteByEmployeeId(empId);
            for (Object o : asList(b.get("education"))) {
                Map<String, Object> row = (Map<String, Object>) o;
                String school = strOf(row.get("schoolName"));
                String level  = strOf(row.get("level"));
                if (isBlank(school) && isBlank(strOf(row.get("yearGraduated")))) continue;
                EmployeeEducation ed = new EmployeeEducation();
                ed.setEmployeeId(empId);
                ed.setLevel(level != null ? level.toUpperCase() : "TERTIARY");
                ed.setSchoolName(school);
                ed.setYearGraduated(strOf(row.get("yearGraduated")));
                educationRepository.save(ed);
            }
        }
        if (b.containsKey("workHistory")) {
            workHistoryRepository.deleteByEmployeeId(empId);
            for (Object o : asList(b.get("workHistory"))) {
                Map<String, Object> row = (Map<String, Object>) o;
                if (isBlank(strOf(row.get("employerName")))) continue;
                EmployeeWorkHistory w = new EmployeeWorkHistory();
                w.setEmployeeId(empId);
                w.setEmployerName(strOf(row.get("employerName")));
                w.setYearStarted(strOf(row.get("yearStarted")));
                w.setYearEnded(strOf(row.get("yearEnded")));
                w.setPosition(strOf(row.get("position")));
                workHistoryRepository.save(w);
            }
        }
        if (b.containsKey("benefits")) {
            benefitRepository.deleteByEmployeeId(empId);
            for (Object o : asList(b.get("benefits"))) {
                Map<String, Object> row = (Map<String, Object>) o;
                Object btId = row.get("benefitTypeId");
                if (btId == null) continue;
                EmployeeBenefit bn = new EmployeeBenefit();
                bn.setEmployeeId(empId);
                bn.setBenefitTypeId(Long.valueOf(btId.toString()));
                bn.setAmount(parseDecimal(row.get("amount")));
                bn.setNotes(strOf(row.get("notes")));
                benefitRepository.save(bn);
            }
        }
    }

    private void addEvent(Long empId, String type, String oldV, String newV, String details, Long adminId) {
        EmployeeEvent ev = new EmployeeEvent();
        ev.setEmployeeId(empId);
        ev.setEventType(type);
        ev.setOldValue(oldV);
        ev.setNewValue(newV);
        ev.setDetails(details);
        ev.setCreatedBy(adminId);
        eventRepository.save(ev);
    }

    private Map<String, Object> summary(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               e.getId());
        m.put("employeeCode",     e.getEmployeeCode());
        m.put("fullName",         fullName(e));
        m.put("position",         e.getPosition());
        m.put("employmentStatus", e.getEmploymentStatus());
        m.put("status",           e.getStatus());
        m.put("dateOfEmployment", e.getDateOfEmployment() != null ? e.getDateOfEmployment().toString() : null);
        m.put("photo",            e.getPhoto());
        return m;
    }

    private Map<String, Object> fullDetail(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                e.getId());
        m.put("employeeCode",      e.getEmployeeCode());
        m.put("lastName",          e.getLastName());
        m.put("firstName",         e.getFirstName());
        m.put("middleName",        e.getMiddleName());
        m.put("maidenName",        e.getMaidenName());
        m.put("fullName",          fullName(e));
        m.put("birthdate",         e.getBirthdate() != null ? e.getBirthdate().toString() : null);
        m.put("age",               e.getBirthdate() != null ? Period.between(e.getBirthdate(), LocalDate.now()).getYears() : null);
        m.put("nationality",       e.getNationality());
        m.put("civilStatus",       e.getCivilStatus());
        m.put("gender",            e.getGender());
        m.put("position",          e.getPosition());
        m.put("dateOfEmployment",  e.getDateOfEmployment() != null ? e.getDateOfEmployment().toString() : null);
        m.put("email",             e.getEmail());
        m.put("spouseName",        e.getSpouseName());
        m.put("contactNumber",     e.getContactNumber());
        m.put("address",           e.getAddress());
        m.put("sssNumber",         e.getSssNumber());
        m.put("pagibigNumber",     e.getPagibigNumber());
        m.put("philhealthNumber",  e.getPhilhealthNumber());
        m.put("photo",             e.getPhoto());
        m.put("employmentStatus",  e.getEmploymentStatus());
        m.put("probationEndDate",  e.getProbationEndDate() != null ? e.getProbationEndDate().toString() : null);
        m.put("dailyWage",         e.getDailyWage());
        m.put("status",            e.getStatus());

        m.put("education", educationRepository.findByEmployeeId(e.getId()).stream().map(ed -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("level", ed.getLevel()); r.put("schoolName", ed.getSchoolName()); r.put("yearGraduated", ed.getYearGraduated());
            return r;
        }).collect(Collectors.toList()));

        m.put("workHistory", workHistoryRepository.findByEmployeeId(e.getId()).stream().map(w -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employerName", w.getEmployerName()); r.put("yearStarted", w.getYearStarted());
            r.put("yearEnded", w.getYearEnded()); r.put("position", w.getPosition());
            return r;
        }).collect(Collectors.toList()));

        m.put("benefits", benefitRepository.findByEmployeeId(e.getId()).stream().map(bn -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("benefitTypeId", bn.getBenefitTypeId()); r.put("amount", bn.getAmount()); r.put("notes", bn.getNotes());
            return r;
        }).collect(Collectors.toList()));

        m.put("events", eventRepository.findByEmployeeIdOrderByEventDateDescIdDesc(e.getId()).stream()
                .map(this::eventMap).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> eventMap(EmployeeEvent ev) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",        ev.getId());
        r.put("eventType", ev.getEventType());
        r.put("eventDate", ev.getEventDate() != null ? ev.getEventDate().toString() : null);
        r.put("oldValue",  ev.getOldValue());
        r.put("newValue",  ev.getNewValue());
        r.put("details",   ev.getDetails());
        return r;
    }

    private Map<String, Object> benefitTypeMap(BenefitType bt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", bt.getId()); r.put("name", bt.getName());
        r.put("isGovernment", bt.getIsGovernment()); r.put("active", bt.getActive());
        return r;
    }

    private String fullName(Employee e) {
        StringBuilder sb = new StringBuilder();
        if (e.getFirstName() != null)  sb.append(e.getFirstName());
        if (e.getMiddleName() != null && !e.getMiddleName().isBlank()) sb.append(' ').append(e.getMiddleName());
        if (e.getLastName() != null)   sb.append(' ').append(e.getLastName());
        return sb.toString().trim();
    }

    private boolean isAdmin(String authHeader) {
        Long uid = userIdFromHeader(authHeader);
        if (uid == null) return false;
        User u = userRepository.findById(uid).orElse(null);
        return u != null && List.of("SUPER_ADMIN", "ADMIN", "ADMINISTRATOR").contains(u.getRole());
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private List<?> asList(Object o) { return (o instanceof List<?> l) ? l : Collections.emptyList(); }
    private String strOf(Object o)   { return o != null ? o.toString().trim() : null; }
    private String trim(Map<String, Object> b, String k) { Object v = b.get(k); return v != null ? v.toString().trim() : null; }
    private String upper(String s)   { return s != null ? s.toUpperCase() : null; }
    private boolean isBlank(String s){ return s == null || s.isEmpty(); }
    private String moneyStr(BigDecimal v) { return v != null ? "₱" + v.stripTrailingZeros().toPlainString() : "—"; }

    private LocalDate parseDate(Map<String, Object> b, String k) {
        String v = trim(b, k);
        if (isBlank(v)) return null;
        try { return LocalDate.parse(v); } catch (Exception e) { return null; }
    }
    private BigDecimal parseDecimal(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }
}
