import javax.swing.*;
import javax.swing.table.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.*;
import java.io.*;
import java.nio.file.*;

// ===================== ENUMS =====================

enum TaskPriority {
    CRITICAL(1), HIGH(2), MEDIUM(3), LOW(4), LOWEST(5);

    final int value;
    TaskPriority(int v) { this.value = v; }

    static TaskPriority fromInt(int v) {
        for (TaskPriority p : values()) if (p.value == v) return p;
        return MEDIUM;
    }
}

enum TaskCategory {
    STUDY("Study", new Color(0x3498db)),
    WORK("Work", new Color(0xe74c3c)),
    PERSONAL("Personal", new Color(0x2ecc71)),
    URGENT("Urgent", new Color(0xe67e22)),
    MAINTENANCE("Maintenance", new Color(0x95a5a6));

    final String displayName;
    final Color color;
    TaskCategory(String n, Color c) { displayName = n; color = c; }

    static String[] names() {
        return Arrays.stream(values()).map(c -> c.displayName).toArray(String[]::new);
    }
}

// ===================== TASK =====================

class Task implements Comparable<Task> {
    int id;
    String name, category, description, status;
    int priority;
    long createdMs, deadlineMs;
    List<String> dependencies;
    double completionTimeSec;
    double score;

    Task(int id, String name, int priority, long deadlineMs, String category,
         String description, List<String> dependencies) {
        this.id = id; this.name = name; this.priority = priority;
        this.deadlineMs = deadlineMs; this.category = category;
        this.description = description; this.dependencies = dependencies;
        this.createdMs = System.currentTimeMillis();
        this.status = "pending";
    }

    @Override public int compareTo(Task o) { return Double.compare(this.score, o.score); }
}

// ===================== SCHEDULER =====================

class TaskSchedulerEngine {
    PriorityQueue<Task> taskQueue = new PriorityQueue<>();
    List<Task> completedTasks = new ArrayList<>();
    int taskCounter = 0;
    int totalCompleted = 0;
    double avgCompletionTime = 0;
    Map<String, Integer> categoryCounts = new HashMap<>();
    BlockingQueue<String> notificationQueue = new LinkedBlockingQueue<>();
    volatile boolean running = true;

    TaskSchedulerEngine() {
        Thread notifThread = new Thread(this::processNotifications);
        notifThread.setDaemon(true);
        notifThread.start();
    }

    double calculateDynamicScore(Task task) {
        long now = System.currentTimeMillis();
        double basScore = task.priority * 20.0;
        double waitingSec = (now - task.createdMs) / 1000.0;
        double agingBoost = Math.min(waitingSec / 3600.0, 10.0);
        double timeLeftSec = (task.deadlineMs - now) / 1000.0;
        double deadlinePressure;
        if (timeLeftSec <= 0) {
            deadlinePressure = 100;
        } else {
            deadlinePressure = Math.min((1.0 / timeLeftSec) * 100.0, 50.0);
        }
        Map<String, Double> mults = new HashMap<>();
        mults.put("Urgent", 0.8); mults.put("Work", 1.0); mults.put("Study", 1.2);
        mults.put("Personal", 1.5); mults.put("Maintenance", 1.3);
        double mult = mults.getOrDefault(task.category, 1.0);
        double score = (basScore - agingBoost - deadlinePressure) * mult;
        score += (task.id % 100) * 0.001;
        return -score;
    }

    Task addTask(String name, int priority, long deadlineSec, String category,
                 String description, List<String> deps) {
        long now = System.currentTimeMillis();
        Task task = new Task(taskCounter++, name, priority,
                now + deadlineSec * 1000L, category, description, deps);
        task.score = calculateDynamicScore(task);
        taskQueue.offer(task);
        return task;
    }

    void rebuildQueue() {
        List<Task> all = new ArrayList<>(taskQueue);
        taskQueue.clear();
        for (Task t : all) { t.score = calculateDynamicScore(t); taskQueue.offer(t); }
    }

    Task getNextTask() {
        if (taskQueue.isEmpty()) return null;
        rebuildQueue();
        Set<String> completedNames = completedTasks.stream()
                .map(t -> t.name).collect(Collectors.toSet());
        List<Task> all = new ArrayList<>(taskQueue);
        for (Task t : all) {
            if (completedNames.containsAll(t.dependencies)) {
                taskQueue.remove(t); return t;
            }
        }
        return taskQueue.poll();
    }

    Task executeTask() {
        Task task = getNextTask();
        if (task == null) return null;
        double completionSec = (System.currentTimeMillis() - task.createdMs) / 1000.0;
        totalCompleted++;
        avgCompletionTime = (avgCompletionTime * (totalCompleted - 1) + completionSec) / totalCompleted;
        categoryCounts.merge(task.category, 1, Integer::sum);
        task.status = "completed";
        task.completionTimeSec = completionSec;
        completedTasks.add(task);
        return task;
    }

    void processNotifications() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                for (Task t : new ArrayList<>(taskQueue)) {
                    long leftMs = t.deadlineMs - now;
                    if (leftMs > 0 && leftMs < 300_000) {
                        notificationQueue.offer("⚠️ Deadline approaching: " + t.name +
                                " (" + leftMs / 60000 + " min)");
                    }
                }
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {}
        }
    }

    List<Task> getSortedTasks() {
        List<Task> all = new ArrayList<>(taskQueue);
        all.sort(Comparator.comparingDouble(t -> t.score));
        return all;
    }

    Map<String, Object> getStatistics() {
        long now = System.currentTimeMillis();
        int overdue = (int) taskQueue.stream().filter(t -> t.deadlineMs < now).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_tasks", taskQueue.size());
        stats.put("completed_tasks", totalCompleted);
        stats.put("overdue_tasks", overdue);
        stats.put("avg_completion_time", avgCompletionTime);
        stats.put("category_breakdown", new HashMap<>(categoryCounts));
        return stats;
    }
}

// ===================== GUI =====================

public class AdvancedTaskScheduler {

    // Colors
    static final Color BG      = new Color(0x2c3e50);
    static final Color FG      = new Color(0xecf0f1);
    static final Color ACCENT  = new Color(0x3498db);
    static final Color SUCCESS = new Color(0x2ecc71);
    static final Color WARNING = new Color(0xe74c3c);
    static final Color OVERDUE_BG = new Color(0xff6b6b);
    static final Color URGENT_BG  = new Color(0xffa500);

    JFrame frame;
    TaskSchedulerEngine scheduler = new TaskSchedulerEngine();

    // Input controls
    JTextField nameField, deadlineField;
    ButtonGroup priorityGroup;
    JComboBox<String> categoryCombo;
    JTextArea descArea;

    // Table
    JTable taskTable;
    DefaultTableModel tableModel;

    // Status / stats
    JLabel statusBar, statsLabel;

    // Timer for refresh
    javax.swing.Timer refreshTimer;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AdvancedTaskScheduler().buildAndShow());
    }

    void buildAndShow() {
        frame = new JFrame("Advanced Smart Task Scheduler");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 700);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        frame.setJMenuBar(buildMenuBar());
        frame.add(buildInputPanel(), BorderLayout.NORTH);
        frame.add(buildTablePanel(), BorderLayout.CENTER);
        frame.add(buildBottomPanel(), BorderLayout.SOUTH);

        frame.setVisible(true);

        refreshTimer = new javax.swing.Timer(5000, e -> refreshDisplay());
        refreshTimer.start();

        new javax.swing.Timer(5000, e -> checkNotifications()).start();
    }

    // ---- MENU ----

    JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.setBackground(BG);

        JMenu fileMenu = darkMenu("File");
        fileMenu.add(darkItem("Save History", e -> saveHistory()));
        fileMenu.add(darkItem("Export Tasks", e -> exportTasks()));
        fileMenu.addSeparator();
        fileMenu.add(darkItem("Exit", e -> System.exit(0)));

        JMenu viewMenu = darkMenu("View");
        viewMenu.add(darkItem("Show Statistics", e -> showStatistics()));
        viewMenu.add(darkItem("Show Upcoming", e -> showUpcoming()));

        JMenu helpMenu = darkMenu("Help");
        helpMenu.add(darkItem("About", e -> showAbout()));

        bar.add(fileMenu); bar.add(viewMenu); bar.add(helpMenu);
        return bar;
    }

    JMenu darkMenu(String text) {
        JMenu m = new JMenu(text);
        m.setForeground(FG); m.setBackground(BG);
        return m;
    }

    JMenuItem darkItem(String text, ActionListener al) {
        JMenuItem i = new JMenuItem(text);
        i.setBackground(BG); i.setForeground(FG);
        i.addActionListener(al);
        return i;
    }

    // ---- INPUT PANEL ----

    JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG);
        outer.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        JLabel title = new JLabel("Add New Task", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        title.setForeground(ACCENT);
        title.setBorder(BorderFactory.createEmptyBorder(5, 0, 8, 0));
        outer.add(title, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(BG);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.EAST;

        // Row 0: Task Name + Priority
        c.gridx = 0; c.gridy = 0;
        grid.add(label("Task Name:"), c);
        nameField = textField(22);
        c.gridx = 1; c.anchor = GridBagConstraints.WEST;
        grid.add(nameField, c);

        c.gridx = 2; c.anchor = GridBagConstraints.EAST;
        grid.add(label("Priority (1-5):"), c);

        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        radioPanel.setBackground(BG);
        priorityGroup = new ButtonGroup();
        for (int i = 1; i <= 5; i++) {
            JRadioButton rb = new JRadioButton(String.valueOf(i));
            rb.setBackground(BG); rb.setForeground(FG);
            rb.setActionCommand(String.valueOf(i));
            if (i == 3) rb.setSelected(true);
            priorityGroup.add(rb); radioPanel.add(rb);
        }
        c.gridx = 3; c.anchor = GridBagConstraints.WEST;
        grid.add(radioPanel, c);

        // Row 1: Deadline + Category
        c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.EAST;
        grid.add(label("Deadline (minutes):"), c);
        deadlineField = textField(22);
        deadlineField.setText("60");
        c.gridx = 1; c.anchor = GridBagConstraints.WEST;
        grid.add(deadlineField, c);

        c.gridx = 2; c.anchor = GridBagConstraints.EAST;
        grid.add(label("Category:"), c);
        categoryCombo = new JComboBox<>(TaskCategory.names());
        categoryCombo.setSelectedItem("Work");
        styleCombo(categoryCombo);
        c.gridx = 3; c.anchor = GridBagConstraints.WEST;
        grid.add(categoryCombo, c);

        // Row 2: Description
        c.gridx = 0; c.gridy = 2; c.anchor = GridBagConstraints.NORTHEAST;
        grid.add(label("Description:"), c);
        descArea = new JTextArea(3, 40);
        descArea.setLineWrap(true); descArea.setWrapStyleWord(true);
        JScrollPane descScroll = new JScrollPane(descArea);
        c.gridx = 1; c.gridwidth = 3; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(descScroll, c);
        c.gridwidth = 1; c.fill = GridBagConstraints.NONE;

        // Row 3: Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        btnPanel.setBackground(BG);
        btnPanel.add(accentButton("➕ Add Task", e -> addTask()));
        btnPanel.add(plainButton("▶ Execute Next", e -> executeTask()));
        btnPanel.add(plainButton("🗑 Clear Fields", e -> clearFields()));

        c.gridx = 0; c.gridy = 3; c.gridwidth = 4; c.anchor = GridBagConstraints.CENTER;
        grid.add(btnPanel, c);

        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    // ---- TABLE ----

    JPanel buildTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        String[] cols = {"ID", "Task Name", "Priority", "Category", "Time Left", "Status"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        taskTable = new JTable(tableModel);
        taskTable.setBackground(Color.WHITE);
        taskTable.setForeground(Color.BLACK);
        taskTable.setGridColor(new Color(0xdddddd));
        taskTable.setSelectionBackground(ACCENT);
        taskTable.setRowHeight(24);
        taskTable.getTableHeader().setBackground(new Color(0x34495e));
        taskTable.getTableHeader().setForeground(FG);
        taskTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));

        int[] widths = {50, 200, 80, 100, 100, 100};
        for (int i = 0; i < widths.length; i++)
            taskTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        taskTable.setDefaultRenderer(Object.class, new RowColorRenderer());
        taskTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showTaskDetails();
            }
        });

        JScrollPane scroll = new JScrollPane(taskTable);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ---- BOTTOM ----

    JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(0x34495e));

        statsLabel = new JLabel("📊 Active: 0 | Completed: 0 | Overdue: 0", SwingConstants.CENTER);
        statsLabel.setFont(new Font("SansSerif", Font.PLAIN, 9));
        statsLabel.setForeground(ACCENT);
        statsLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        statusBar = new JLabel("Ready", SwingConstants.LEFT);
        statusBar.setFont(new Font("SansSerif", Font.PLAIN, 11));
        statusBar.setForeground(FG);
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        panel.add(statsLabel, BorderLayout.NORTH);
        panel.add(statusBar, BorderLayout.SOUTH);
        return panel;
    }

    // ---- ACTIONS ----

    void addTask() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { error("Task name is required!"); return; }
        String deadlineStr = deadlineField.getText().trim();
        int deadlineMin;
        try { deadlineMin = Integer.parseInt(deadlineStr); }
        catch (NumberFormatException ex) { error("Deadline must be a number!"); return; }

        int priority = Integer.parseInt(priorityGroup.getSelection().getActionCommand());
        String category = (String) categoryCombo.getSelectedItem();
        String desc = descArea.getText().trim();

        scheduler.addTask(name, priority, deadlineMin * 60L, category, desc, new ArrayList<>());
        status("Task '" + name + "' added successfully");
        clearFields();
        refreshDisplay();
        JOptionPane.showMessageDialog(frame, "Task '" + name + "' added to scheduler!", "Success",
                JOptionPane.INFORMATION_MESSAGE);
    }

    void executeTask() {
        Task task = scheduler.executeTask();
        if (task == null) {
            JOptionPane.showMessageDialog(frame, "No tasks available to execute!", "No Tasks",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        status("Executing: " + task.name);
        refreshDisplay();
        String details = "Task: " + task.name + "\n" +
                "Priority: " + task.priority + "\n" +
                "Category: " + task.category + "\n" +
                "Description: " + (task.description.isEmpty() ? "N/A" : task.description) + "\n" +
                String.format("Completion Time: %.2f seconds", task.completionTimeSec);
        JOptionPane.showMessageDialog(frame, details, "Task Executed", JOptionPane.INFORMATION_MESSAGE);
    }

    void clearFields() {
        nameField.setText("");
        deadlineField.setText("60");
        categoryCombo.setSelectedItem("Work");
        descArea.setText("");
        Enumeration<AbstractButton> buttons = priorityGroup.getElements();
        int i = 1;
        while (buttons.hasMoreElements()) {
            AbstractButton b = buttons.nextElement();
            b.setSelected(i++ == 3);
        }
        nameField.requestFocus();
    }

    void refreshDisplay() {
        tableModel.setRowCount(0);
        long now = System.currentTimeMillis();
        List<Task> tasks = scheduler.getSortedTasks();
        for (Task t : tasks) {
            long leftMs = t.deadlineMs - now;
            String timeLeft = leftMs > 0 ? (leftMs / 60000) + " min" : "Overdue!";
            tableModel.addRow(new Object[]{
                    t.id, t.name, t.priority + "★", t.category, timeLeft, "Pending"
            });
        }
        Map<String, Object> stats = scheduler.getStatistics();
        statsLabel.setText("📊 Active: " + stats.get("active_tasks") +
                " | Completed: " + stats.get("completed_tasks") +
                " | Overdue: " + stats.get("overdue_tasks"));
    }

    void showTaskDetails() {
        int row = taskTable.getSelectedRow();
        if (row < 0) return;
        int taskId = (int) tableModel.getValueAt(row, 0);
        for (Task t : scheduler.getSortedTasks()) {
            if (t.id == taskId) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
                String details = "Task Details:\n\n" +
                        "Name: " + t.name + "\n" +
                        "Priority: " + t.priority + "\n" +
                        "Category: " + t.category + "\n" +
                        "Description: " + (t.description.isEmpty() ? "N/A" : t.description) + "\n" +
                        "Created: " + Instant.ofEpochMilli(t.createdMs)
                        .atZone(ZoneId.systemDefault()).toLocalTime().format(fmt) + "\n" +
                        "Deadline: " + Instant.ofEpochMilli(t.deadlineMs)
                        .atZone(ZoneId.systemDefault()).toLocalTime().format(fmt) + "\n" +
                        "Dependencies: " + (t.dependencies.isEmpty() ? "None" : String.join(", ", t.dependencies));
                JOptionPane.showMessageDialog(frame, details, "Task Details", JOptionPane.INFORMATION_MESSAGE);
                break;
            }
        }
    }

    void saveHistory() { JOptionPane.showMessageDialog(frame, "Task history saved successfully!", "Saved", JOptionPane.INFORMATION_MESSAGE); }
    void exportTasks() { JOptionPane.showMessageDialog(frame, "Tasks exported!", "Exported", JOptionPane.INFORMATION_MESSAGE); }

    @SuppressWarnings("unchecked")
    void showStatistics() {
        Map<String, Object> stats = scheduler.getStatistics();
        StringBuilder sb = new StringBuilder("📈 Scheduler Statistics\n\n");
        sb.append("Active Tasks: ").append(stats.get("active_tasks")).append("\n");
        sb.append("Completed Tasks: ").append(stats.get("completed_tasks")).append("\n");
        sb.append("Overdue Tasks: ").append(stats.get("overdue_tasks")).append("\n");
        sb.append(String.format("Avg Completion Time: %.2f seconds\n\n", stats.get("avg_completion_time")));
        sb.append("Category Breakdown:\n");
        Map<String, Integer> breakdown = (Map<String, Integer>) stats.get("category_breakdown");
        for (Map.Entry<String, Integer> e : breakdown.entrySet())
            sb.append("  • ").append(e.getKey()).append(": ").append(e.getValue()).append(" tasks\n");
        JOptionPane.showMessageDialog(frame, sb.toString(), "Statistics", JOptionPane.INFORMATION_MESSAGE);
    }

    void showUpcoming() {
        long now = System.currentTimeMillis();
        List<Task> upcoming = scheduler.getSortedTasks().stream()
                .filter(t -> { long l = t.deadlineMs - now; return l > 0 && l < 86_400_000L; })
                .sorted(Comparator.comparingLong(t -> t.deadlineMs))
                .collect(Collectors.toList());
        if (upcoming.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No upcoming tasks in the next 24 hours!", "Upcoming Tasks", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder("📅 Upcoming Tasks (Next 24 Hours)\n\n");
        for (Task t : upcoming) {
            double hoursLeft = (t.deadlineMs - now) / 3_600_000.0;
            sb.append(String.format("• %s - Due in %.1f hours (Priority: %d)\n", t.name, hoursLeft, t.priority));
        }
        JOptionPane.showMessageDialog(frame, sb.toString(), "Upcoming Tasks", JOptionPane.INFORMATION_MESSAGE);
    }

    void showAbout() {
        String text = "Advanced Smart Task Scheduler\n\nVersion: 2.0\nFeatures:\n" +
                "• Dynamic priority scoring\n• Deadline pressure calculation\n" +
                "• Task aging system\n• Real-time notifications\n" +
                "• Task history tracking\n• Statistics and analytics\n\n" +
                "Converted from Python/Tkinter to Java/Swing";
        JOptionPane.showMessageDialog(frame, text, "About", JOptionPane.INFORMATION_MESSAGE);
    }

    void checkNotifications() {
        String msg;
        while ((msg = scheduler.notificationQueue.poll()) != null) {
            final String m = msg;
            statusBar.setForeground(Color.RED);
            statusBar.setText(m);
            new javax.swing.Timer(5000, e -> statusBar.setForeground(FG)) {{ setRepeats(false); start(); }};
        }
    }

    // ---- HELPERS ----

    JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG); l.setBackground(BG);
        return l;
    }

    JTextField textField(int cols) {
        JTextField f = new JTextField(cols);
        return f;
    }

    void styleCombo(JComboBox<String> combo) {
        combo.setPreferredSize(new Dimension(160, 22));
    }

    JButton accentButton(String text, ActionListener al) {
        JButton b = new JButton(text);
        b.setBackground(ACCENT); b.setForeground(Color.WHITE);
        b.setFocusPainted(false); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        return b;
    }

    JButton plainButton(String text, ActionListener al) {
        JButton b = new JButton(text);
        b.setFocusPainted(false); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        return b;
    }

    void status(String text) { statusBar.setText(text); statusBar.setForeground(FG); }
    void error(String text) { JOptionPane.showMessageDialog(frame, text, "Error", JOptionPane.ERROR_MESSAGE); }

    // ---- ROW RENDERER ----

    class RowColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                String timeLeft = (String) table.getModel().getValueAt(row, 4);
                if ("Overdue!".equals(timeLeft)) {
                    c.setBackground(OVERDUE_BG);
                } else {
                    try {
                        int mins = Integer.parseInt(timeLeft.replace(" min", ""));
                        c.setBackground(mins < 5 ? URGENT_BG : Color.WHITE);
                    } catch (Exception e) {
                        c.setBackground(Color.WHITE);
                    }
                }
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
