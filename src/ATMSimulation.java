import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// ============================================================
// Модель банківського рахунку (потокобезпечна)
// ============================================================
class BankAccount {
    private final String id;
    private final String owner;
    private double balance;
    private boolean active;
    private final ReentrantLock lock = new ReentrantLock();

    public static final double MAX_WITHDRAWAL = 5000.0;

    public BankAccount(String id, String owner, double initialBalance) {
        this.id = id;
        this.owner = owner;
        this.balance = initialBalance;
        this.active = true;
    }

    public String getId() { return id; }
    public String getOwner() { return owner; }
    public boolean isActive() { return active; }

    public double getBalance() {
        lock.lock();
        try { return balance; }
        finally { lock.unlock(); }
    }

    /** Поповнення рахунку. Повертає новий баланс або кидає виняток. */
    public double deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Сума поповнення має бути більше 0");
        lock.lock();
        try {
            if (!active) throw new IllegalStateException("Рахунок закрито");
            balance += amount;
            return balance;
        } finally { lock.unlock(); }
    }

    /** Зняття грошей. Повертає новий баланс або кидає виняток. */
    public double withdraw(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Сума зняття має бути більше 0");
        if (amount > MAX_WITHDRAWAL)
            throw new IllegalArgumentException(
                    "Перевищено ліміт одноразового зняття (" + MAX_WITHDRAWAL + " грн)");
        lock.lock();
        try {
            if (!active) throw new IllegalStateException("Рахунок закрито");
            if (amount > balance) throw new IllegalStateException("Недостатньо коштів");
            balance -= amount;
            return balance;
        } finally { lock.unlock(); }
    }

    /** Закриття рахунку. */
    public void close() {
        lock.lock();
        try { active = false; }
        finally { lock.unlock(); }
    }
}

// ============================================================
// Банк — зберігає всі рахунки та веде лог (потокобезпечний)
// ============================================================
class Bank {
    private final ConcurrentHashMap<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger idCounter = new AtomicInteger(1000);
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    public BankAccount openAccount(String owner, double initialBalance) {
        String id = "ACC-" + idCounter.incrementAndGet();
        BankAccount acc = new BankAccount(id, owner, initialBalance);
        accounts.put(id, acc);
        logEvent("ВІДКРИТТЯ", id, owner,
                String.format("Початковий баланс: %.2f грн", initialBalance));
        return acc;
    }

    public void closeAccount(String id) {
        BankAccount acc = getAccount(id);
        acc.close();
        logEvent("ЗАКРИТТЯ", id, acc.getOwner(),
                String.format("Залишок при закритті: %.2f грн", acc.getBalance()));
    }

    public double deposit(String id, double amount) {
        BankAccount acc = getAccount(id);
        double newBalance = acc.deposit(amount);
        logEvent("ПОПОВНЕННЯ", id, acc.getOwner(),
                String.format("+%.2f грн -> баланс: %.2f грн", amount, newBalance));
        return newBalance;
    }

    public double withdraw(String id, double amount) {
        BankAccount acc = getAccount(id);
        double newBalance = acc.withdraw(amount);
        logEvent("ЗНЯТТЯ", id, acc.getOwner(),
                String.format("-%.2f грн -> баланс: %.2f грн", amount, newBalance));
        return newBalance;
    }

    private BankAccount getAccount(String id) {
        BankAccount acc = accounts.get(id);
        if (acc == null) throw new IllegalArgumentException("Рахунок не знайдено: " + id);
        return acc;
    }

    public Collection<BankAccount> getAllAccounts() { return accounts.values(); }

    public List<String> getLog() { return new ArrayList<>(log); }

    private void logEvent(String type, String accId, String owner, String details) {
        String entry = String.format("[%s] %-10s | %s | %s | %s",
                sdf.format(new Date()), type, accId, owner, details);
        log.add(entry);
    }
}

// ============================================================
// Головний GUI — банкомат
// ============================================================
public class ATMSimulation extends JFrame {

    private final Bank bank = new Bank();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Панель рахунків
    private DefaultTableModel accountTableModel;
    private JTable accountTable;

    // Панель логів
    private JTextArea logArea;

    // Поля вводу
    private JTextField ownerField, initBalanceField, accIdField, amountField;
    private JLabel statusLabel;

    // Для симуляції паралельних користувачів
    private JSpinner userCountSpinner;
    private JButton simulateBtn;

    // Лог симуляції — поле класу, щоб не шукати через ієрархію компонентів
    private JTextArea simLog;

    public ATMSimulation() {
        super("Симуляція Банкомату — КПП Лабораторна №6");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 650);
        setLocationRelativeTo(null);
        buildUI();
        seedDemoData();
    }

    // ----------------------------------------------------------
    // Побудова інтерфейсу
    // ----------------------------------------------------------
    private void buildUI() {
        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab("Операції", buildOperationsPanel());
        tabs.addTab("Паралельна симуляція", buildSimulationPanel());
        tabs.addTab("Журнал операцій", buildLogPanel());

        add(tabs, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ------ Вкладка операцій ------
    private JPanel buildOperationsPanel() {
        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel left = new JPanel(new GridBagLayout());
        left.setBorder(BorderFactory.createTitledBorder("Дії"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.fill = GridBagConstraints.HORIZONTAL;

        // --- Відкриття рахунку ---
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        left.add(new JLabel("--- Відкрити рахунок ---"), g);

        g.gridy = 1; g.gridwidth = 1;
        left.add(new JLabel("Власник:"), g);
        g.gridx = 1;
        ownerField = new JTextField(12);
        left.add(ownerField, g);

        g.gridx = 0; g.gridy = 2;
        left.add(new JLabel("Поч. баланс (грн):"), g);
        g.gridx = 1;
        initBalanceField = new JTextField("1000", 12);
        left.add(initBalanceField, g);

        g.gridx = 0; g.gridy = 3; g.gridwidth = 2;
        JButton openBtn = new JButton("Відкрити рахунок");
        openBtn.addActionListener(e -> doOpenAccount());
        left.add(openBtn, g);

        // --- Операції з рахунком ---
        g.gridy = 4; g.gridwidth = 2;
        left.add(new JSeparator(), g);

        g.gridy = 5; g.gridwidth = 2;
        left.add(new JLabel("--- Операції з рахунком ---"), g);

        g.gridy = 6; g.gridwidth = 1; g.gridx = 0;
        left.add(new JLabel("ID рахунку:"), g);
        g.gridx = 1;
        accIdField = new JTextField(12);
        left.add(accIdField, g);

        g.gridx = 0; g.gridy = 7;
        left.add(new JLabel("Сума (грн):"), g);
        g.gridx = 1;
        amountField = new JTextField(12);
        left.add(amountField, g);

        g.gridx = 0; g.gridy = 8; g.gridwidth = 2;
        JButton depositBtn = new JButton("Поповнити");
        depositBtn.addActionListener(e -> doDeposit());
        left.add(depositBtn, g);

        g.gridy = 9;
        JButton withdrawBtn = new JButton("Зняти гроші");
        withdrawBtn.addActionListener(e -> doWithdraw());
        left.add(withdrawBtn, g);

        g.gridy = 10;
        JButton closeBtn = new JButton("Закрити рахунок");
        closeBtn.addActionListener(e -> doCloseAccount());
        left.add(closeBtn, g);

        g.gridy = 11;
        JLabel limitLabel = new JLabel("Ліміт зняття: " + BankAccount.MAX_WITHDRAWAL + " грн");
        limitLabel.setForeground(Color.GRAY);
        left.add(limitLabel, g);

        // Таблиця рахунків
        String[] cols = {"ID", "Власник", "Баланс (грн)", "Статус"};
        accountTableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        accountTable = new JTable(accountTableModel);
        accountTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountTable.getSelectionModel().addListSelectionListener(e -> {
            int row = accountTable.getSelectedRow();
            if (row >= 0) {
                accIdField.setText((String) accountTableModel.getValueAt(row, 0));
            }
        });

        JScrollPane tableScroll = new JScrollPane(accountTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Рахунки"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tableScroll);
        split.setDividerLocation(280);
        main.add(split, BorderLayout.CENTER);

        return main;
    }

    // ------ Вкладка симуляції ------
    private JPanel buildSimulationPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Кількість паралельних користувачів:"));
        userCountSpinner = new JSpinner(new SpinnerNumberModel(5, 2, 20, 1));
        controls.add(userCountSpinner);
        simulateBtn = new JButton("Запустити симуляцію");
        simulateBtn.addActionListener(e -> runSimulation());
        controls.add(simulateBtn);

        simLog = new JTextArea();
        simLog.setEditable(false);
        simLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(simLog), BorderLayout.CENTER);

        JLabel hint = new JLabel(
                "<html><i>Симуляція запускає N потоків одночасно — кожен виконує випадкові операції над рахунками.</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        panel.add(hint, BorderLayout.SOUTH);

        return panel;
    }

    // ------ Вкладка логів ------
    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton refreshLogBtn = new JButton("Оновити журнал");
        refreshLogBtn.addActionListener(e -> refreshLog());

        panel.add(refreshLogBtn, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    // ----------------------------------------------------------
    // Обробники операцій
    // ----------------------------------------------------------
    private void doOpenAccount() {
        String owner = ownerField.getText().trim();
        if (owner.isEmpty()) { showError("Введіть ім'я власника"); return; }
        try {
            double init = Double.parseDouble(initBalanceField.getText().trim());
            BankAccount acc = bank.openAccount(owner, init);
            status("Рахунок " + acc.getId() + " відкрито для " + owner);
            refreshTable();
            refreshLog();
            ownerField.setText("");
        } catch (NumberFormatException ex) {
            showError("Некоректна сума початкового балансу");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void doDeposit() {
        try {
            String id = accIdField.getText().trim();
            double amount = Double.parseDouble(amountField.getText().trim());
            double newBal = bank.deposit(id, amount);
            status(String.format("Поповнено %s. Новий баланс: %.2f грн", id, newBal));
            refreshTable(); refreshLog();
        } catch (NumberFormatException ex) { showError("Некоректна сума");
        } catch (Exception ex) { showError(ex.getMessage()); }
    }

    private void doWithdraw() {
        try {
            String id = accIdField.getText().trim();
            double amount = Double.parseDouble(amountField.getText().trim());
            double newBal = bank.withdraw(id, amount);
            status(String.format("Знято з %s. Новий баланс: %.2f грн", id, newBal));
            refreshTable(); refreshLog();
        } catch (NumberFormatException ex) { showError("Некоректна сума");
        } catch (Exception ex) { showError(ex.getMessage()); }
    }

    private void doCloseAccount() {
        String id = accIdField.getText().trim();
        if (id.isEmpty()) { showError("Введіть ID рахунку"); return; }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Закрити рахунок " + id + "?", "Підтвердження", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                bank.closeAccount(id);
                status("Рахунок " + id + " закрито");
                refreshTable(); refreshLog();
            } catch (Exception ex) { showError(ex.getMessage()); }
        }
    }

    // ----------------------------------------------------------
    // Паралельна симуляція
    // ----------------------------------------------------------
    private void runSimulation() {
        int userCount = (int) userCountSpinner.getValue();
        simulateBtn.setEnabled(false);
        simLog.setText("Запуск симуляції з " + userCount + " паралельних користувачів...\n\n");

        List<String> simAccountIds = new ArrayList<>();
        for (int i = 1; i <= userCount; i++) {
            BankAccount acc = bank.openAccount("Симуляція-Юзер-" + i, 3000 + i * 100);
            simAccountIds.add(acc.getId());
        }

        CountDownLatch latch = new CountDownLatch(userCount);
        Random random = new Random();
        String[] ops = {"deposit", "withdraw", "deposit", "withdraw", "deposit"};

        for (int i = 0; i < userCount; i++) {
            final int userId = i + 1;
            final String myId = simAccountIds.get(i);
            executor.submit(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("Користувач ").append(userId)
                        .append(" (").append(myId).append(") починає...\n");
                try {
                    for (int op = 0; op < 3; op++) {
                        Thread.sleep(random.nextInt(300) + 100);
                        String operation = ops[random.nextInt(ops.length)];
                        double amount = 100 + random.nextInt(400);
                        try {
                            if ("deposit".equals(operation)) {
                                double bal = bank.deposit(myId, amount);
                                sb.append("  [OK] Поповнення +").append(String.format("%.0f", amount))
                                        .append(" грн -> ").append(String.format("%.2f", bal)).append(" грн\n");
                            } else {
                                double bal = bank.withdraw(myId, amount);
                                sb.append("  [OK] Зняття -").append(String.format("%.0f", amount))
                                        .append(" грн -> ").append(String.format("%.2f", bal)).append(" грн\n");
                            }
                        } catch (Exception ex) {
                            sb.append("  [!] ").append(ex.getMessage()).append("\n");
                        }
                    }
                } catch (InterruptedException ignored) {}
                sb.append("Користувач ").append(userId).append(" завершив роботу.\n\n");

                final String result = sb.toString();
                SwingUtilities.invokeLater(() -> simLog.append(result));
                latch.countDown();
            });
        }

        executor.submit(() -> {
            try {
                latch.await();
                SwingUtilities.invokeLater(() -> {
                    simLog.append("Симуляція завершена. Всі " + userCount + " користувачів обслужено.\n");
                    simulateBtn.setEnabled(true);
                    refreshTable();
                    refreshLog();
                    status("Симуляцію завершено");
                });
            } catch (InterruptedException ignored) {}
        });
    }

    // ----------------------------------------------------------
    // Допоміжні методи
    // ----------------------------------------------------------
    private void refreshTable() {
        SwingUtilities.invokeLater(() -> {
            accountTableModel.setRowCount(0);
            for (BankAccount acc : bank.getAllAccounts()) {
                accountTableModel.addRow(new Object[]{
                        acc.getId(),
                        acc.getOwner(),
                        String.format("%.2f", acc.getBalance()),
                        acc.isActive() ? "Активний" : "Закрито"
                });
            }
        });
    }

    private void refreshLog() {
        SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder();
            for (String entry : bank.getLog()) {
                sb.append(entry).append("\n");
            }
            logArea.setText(sb.toString());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void seedDemoData() {
        bank.openAccount("Іваненко Олег", 5000);
        bank.openAccount("Петренко Марія", 12000);
        bank.openAccount("Коваль Дмитро", 3500);
        refreshTable();
        refreshLog();
    }

    private void status(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Помилка", JOptionPane.ERROR_MESSAGE);
    }

    // ----------------------------------------------------------
    // Точка входу
    // ----------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new ATMSimulation().setVisible(true);
        });
    }
}