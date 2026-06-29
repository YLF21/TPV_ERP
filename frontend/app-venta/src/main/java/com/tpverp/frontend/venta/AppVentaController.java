package com.tpverp.frontend.venta;

import com.tpverp.frontend.common.sales.ProductCatalog;
import com.tpverp.frontend.common.sales.ProductSnapshot;
import com.tpverp.frontend.common.sales.QuickCommand;
import com.tpverp.frontend.common.sales.SaleLine;
import com.tpverp.frontend.common.sales.TicketSale;
import com.tpverp.frontend.common.sales.PaymentDraft;
import com.tpverp.frontend.common.security.PermissionRules;
import com.tpverp.frontend.common.ui.ProductManagementWindow;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

public class AppVentaController {

    private final TicketSale sale = new TicketSale();
    private final ObservableList<ProductSnapshot> localProducts = FXCollections.observableArrayList(sampleProducts());
    private final ObservableList<ParkedSale> parkedSales = FXCollections.observableArrayList();
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.of("es", "ES"));
    private final DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private int nextParkedSaleId = 1;
    private Stage documentStage;
    private LocalLoginResult session = new LocalLoginResult(false, "", Set.of());

    @FXML
    private ResourceBundle resources;
    @FXML
    private TableView<SaleLine> lineTable;
    @FXML
    private TableColumn<SaleLine, String> codeColumn;
    @FXML
    private TableColumn<SaleLine, String> nameColumn;
    @FXML
    private TableColumn<SaleLine, String> quantityColumn;
    @FXML
    private TableColumn<SaleLine, String> packagesColumn;
    @FXML
    private TableColumn<SaleLine, String> priceColumn;
    @FXML
    private TableColumn<SaleLine, String> discountColumn;
    @FXML
    private TableColumn<SaleLine, String> totalColumn;
    @FXML
    private Label bigTotalLabel;
    @FXML
    private TextField quickField;
    @FXML
    private Label statusLabel;
    @FXML
    private Label lineCountLabel;
    @FXML
    private Label quantitySumLabel;
    @FXML
    private Label packageSumLabel;
    @FXML
    private Label beforeDiscountLabel;
    @FXML
    private Label afterDiscountLabel;

    @FXML
    public void initialize() {
        configureTable();
        lineTable.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            if (newValue.intValue() >= 0 && newValue.intValue() < sale.lines().size()) {
                sale.selectLine(newValue.intValue());
            }
        });
        quickField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleQuickKey);
        refresh();
    }

    public void setSession(LocalLoginResult session) {
        this.session = session;
    }

    @FXML
    private void charge() {
        if (sale.lines().isEmpty()) {
            status(message("status.operationRejected"));
            return;
        }
        openPaymentWindow();
    }

    private void openPaymentWindow() {
        BigDecimal total = sale.totalAfterDiscount();
        Stage dialog = new Stage();
        dialog.setTitle(message("dialog.charge.title"));
        dialog.initOwner(quickField.getScene().getWindow());
        dialog.initModality(Modality.NONE);

        ObservableList<PaymentLine> paymentLines = FXCollections.observableArrayList();

        Label changeLabel = new Label();
        changeLabel.getStyleClass().add("payment-screen-number");
        Label chargedLabel = new Label();
        chargedLabel.getStyleClass().add("payment-screen-number");
        Label missingLabel = new Label();
        missingLabel.getStyleClass().add("payment-footer-number");
        TextField receivedField = new TextField(total.toPlainString());
        receivedField.setPromptText(message("payment.received"));
        receivedField.getStyleClass().add("payment-amount-field");
        TextField documentField = new TextField();
        documentField.setPromptText(message("payment.documentNo"));
        documentField.getStyleClass().add("payment-text-field");
        TextArea commentField = new TextArea();
        commentField.setPromptText(message("payment.comment"));
        commentField.getStyleClass().add("payment-comment");
        commentField.setPrefRowCount(2);

        ToggleGroup methods = new ToggleGroup();
        ToggleButton cash = paymentMethodButton("payment.method.cash", methods);
        ToggleButton card = paymentMethodButton("payment.method.card", methods);
        ToggleButton transfer = paymentMethodButton("payment.method.transfer", methods);
        ToggleButton voucher = paymentMethodButton("payment.method.voucher", methods);
        ToggleButton other = paymentMethodButton("payment.method.other", methods);
        cash.setSelected(true);

        TableView<PaymentLine> table = new TableView<>(paymentLines);
        table.getStyleClass().add("payment-table");
        TableColumn<PaymentLine, String> methodColumn = new TableColumn<>(message("payment.column.method"));
        methodColumn.setCellValueFactory(data -> text(data.getValue().method()));
        methodColumn.setPrefWidth(185);
        TableColumn<PaymentLine, String> amountColumn = new TableColumn<>(message("payment.column.amount"));
        amountColumn.setCellValueFactory(data -> text(money.format(data.getValue().amount())));
        amountColumn.setPrefWidth(130);
        TableColumn<PaymentLine, String> documentColumn = new TableColumn<>(message("payment.column.document"));
        documentColumn.setCellValueFactory(data -> text(data.getValue().documentNo()));
        documentColumn.setPrefWidth(170);
        TableColumn<PaymentLine, String> commentColumn = new TableColumn<>(message("payment.column.comment"));
        commentColumn.setCellValueFactory(data -> text(data.getValue().comment()));
        commentColumn.setPrefWidth(300);
        table.getColumns().setAll(List.of(methodColumn, amountColumn, documentColumn, commentColumn));

        Runnable refreshPayment = () -> {
            BigDecimal charged = paymentLines.stream()
                    .map(PaymentLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            PaymentDraft draft = new PaymentDraft(total, charged);
            chargedLabel.setText(money.format(charged));
            changeLabel.setText(money.format(draft.difference().max(BigDecimal.ZERO)));
            missingLabel.setText(money.format(draft.remaining()));
        };
        Runnable addCurrentPayment = () -> {
            BigDecimal amount = decimal(receivedField.getText(), total);
            ToggleButton selected = (ToggleButton) methods.getSelectedToggle();
            paymentLines.add(new PaymentLine(selected.getText(), amount, documentField.getText(), commentField.getText()));
            table.getSelectionModel().selectLast();
            receivedField.clear();
            refreshPayment.run();
            receivedField.requestFocus();
        };
        Button addPayment = new Button(message("payment.add"));
        addPayment.setOnAction(event -> addCurrentPayment.run());
        Button clearPayments = new Button(message("payment.clearAll"));
        clearPayments.setOnAction(event -> {
            paymentLines.clear();
            refreshPayment.run();
            receivedField.requestFocus();
        });
        Button back = new Button(message("payment.back"));
        back.setOnAction(event -> dialog.close());

        refreshPayment.run();

        Button confirm = new Button(message("payment.confirm"));
        confirm.setDefaultButton(true);
        confirm.setOnAction(event -> {
            sale.clear();
            status(message("status.paymentRegistered"));
            refresh();
            dialog.close();
        });

        HBox toolbar = new HBox(8, paymentToolbarLabel("payment.toolbar.method"), addPayment, clearPayments,
                paymentToolbarLabel("payment.toolbar.document"), paymentToolbarLabel("payment.toolbar.comment"), back);
        toolbar.getStyleClass().add("payment-toolbar");

        GridPane header = new GridPane();
        header.setHgap(14);
        header.setVgap(10);
        header.add(label("payment.charge"), 0, 0);
        header.add(receivedField, 1, 0);
        header.add(label("payment.change"), 0, 1);
        header.add(changeLabel, 1, 1);
        header.add(label("payment.documentNo"), 2, 0);
        header.add(documentField, 3, 0);
        header.add(label("payment.comment"), 2, 1);
        header.add(commentField, 3, 1);
        GridPane.setHgrow(documentField, Priority.ALWAYS);
        GridPane.setHgrow(commentField, Priority.ALWAYS);
        header.getStyleClass().add("payment-header");

        HBox methodBar = new HBox(0, cash, card, transfer, voucher, other);
        methodBar.getStyleClass().add("payment-method-bar");

        GridPane footer = new GridPane();
        footer.setHgap(12);
        footer.add(label("payment.amount"), 0, 0);
        footer.add(new Label(money.format(total)), 1, 0);
        footer.add(label("payment.charged"), 2, 0);
        footer.add(chargedLabel, 3, 0);
        footer.add(label("payment.missing"), 4, 0);
        footer.add(missingLabel, 5, 0);
        footer.getStyleClass().add("payment-footer");

        HBox actions = new HBox(18, confirm, back);
        actions.getStyleClass().add("payment-actions");

        VBox content = new VBox(toolbar, header, methodBar, table, footer, actions);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getStyleClass().add("payment-window");
        javafx.scene.Scene scene = new javafx.scene.Scene(content, 980, 620);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.MULTIPLY) {
                cash.setSelected(true);
                event.consume();
            } else if (event.getCode() == KeyCode.ADD || event.getCode() == KeyCode.PLUS) {
                card.setSelected(true);
                event.consume();
            } else if (event.getCode() == KeyCode.F8) {
                transfer.setSelected(true);
                event.consume();
            } else if (event.getCode() == KeyCode.F9) {
                voucher.setSelected(true);
                event.consume();
            } else if (event.getCode() == KeyCode.F10) {
                other.setSelected(true);
                event.consume();
            } else if (event.getCode() == KeyCode.INSERT) {
                addCurrentPayment.run();
                event.consume();
            } else if (event.getCode() == KeyCode.F12) {
                paymentLines.clear();
                refreshPayment.run();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                event.consume();
            }
        });
        dialog.setScene(scene);
        dialog.setOnShown(event -> receivedField.requestFocus());
        dialog.setOnHidden(event -> quickField.requestFocus());
        dialog.show();
    }

    private Label label(String key) {
        Label label = new Label(message(key));
        label.getStyleClass().add("payment-label");
        return label;
    }

    private Label paymentToolbarLabel(String key) {
        Label label = new Label(message(key));
        label.getStyleClass().add("payment-toolbar-label");
        return label;
    }

    private ToggleButton paymentMethodButton(String key, ToggleGroup methods) {
        ToggleButton button = new ToggleButton(message(key));
        button.setToggleGroup(methods);
        button.getStyleClass().add("payment-method");
        return button;
    }

    private BigDecimal decimal(String text, BigDecimal fallback) {
        try {
            return new BigDecimal(text == null || text.isBlank() ? fallback.toPlainString() : text.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void handleQuickKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            runCommand("ENTER");
            event.consume();
        } else if (event.getCode() == KeyCode.PAUSE) {
            runCommand("PAUSE");
            event.consume();
        } else if (event.getCode() == KeyCode.SLASH && event.isControlDown()) {
            runCommand("CTRL_SLASH");
            event.consume();
        } else if (event.getCode() == KeyCode.SLASH) {
            runCommand("SLASH");
            event.consume();
        } else if (event.getCode() == KeyCode.MULTIPLY && event.isControlDown()) {
            runCommand("CTRL_ASTERISK");
            event.consume();
        } else if (event.getCode() == KeyCode.PAGE_UP) {
            runCommand("PAGE_UP");
            event.consume();
        } else if (event.getCode() == KeyCode.PAGE_DOWN) {
            charge();
            event.consume();
        } else if (event.getCode() == KeyCode.DELETE) {
            openProductDialog(quickField.getText());
            event.consume();
        } else if (event.getCode() == KeyCode.F7 && event.isControlDown()) {
            openProductManagement();
            event.consume();
        } else if (event.getCode() == KeyCode.G && event.isControlDown()) {
            parkOrRecover();
            event.consume();
        } else if (event.getCode() == KeyCode.F && event.isControlDown()) {
            openDocumentWindow();
            event.consume();
        } else if (event.getCode() == KeyCode.F4 && event.isControlDown()) {
            sale.clear();
            status(message("status.ticketDeleted"));
            refresh();
            event.consume();
        } else if (event.getCode() == KeyCode.F5) {
            status(message("status.userClosed"));
            event.consume();
        }
    }

    private void runCommand(String key) {
        QuickCommand command = QuickCommand.from(quickField.getText(), key);
        try {
            switch (command.action()) {
                case ADD_PRODUCT -> addProduct(quickField.getText());
                case SET_QUANTITY -> sale.setSelectedQuantity(command.value());
                case LINE_DISCOUNT -> sale.applySelectedDiscount(command.value());
                case GLOBAL_DISCOUNT -> sale.applyGlobalDiscount(command.value());
                case PACKAGES -> sale.applySelectedPackages(command.value());
                case CHANGE_PRICE -> sale.changeSelectedPrice(command.value());
                case UNKNOWN -> status(message("status.unknownShortcut"));
            }
            quickField.clear();
            refresh();
        } catch (RuntimeException ex) {
            status(message("status.operationRejected"));
        }
    }

    private void addProduct(String code) {
        ProductSnapshot product = catalog().findByCodeOrBarcode(code).orElse(null);
        if (product == null) {
            status(message("status.productNotFound", code));
            return;
        }
        addProduct(product);
    }

    private void addProduct(ProductSnapshot product) {
        sale.addLine(product);
        status(message("status.productAdded", product.code()));
    }

    private void openProductDialog(String initialSearch) {
        Stage dialog = new Stage();
        dialog.setTitle(message("product.dialog.title"));
        dialog.initOwner(quickField.getScene().getWindow());
        dialog.initModality(Modality.NONE);

        TextField searchField = new TextField();
        searchField.setPromptText(message("product.dialog.searchPrompt"));
        searchField.setText(initialSearch == null ? "" : initialSearch.trim());
        searchField.getStyleClass().add("dialog-search-field");

        TableView<ProductSnapshot> table = new TableView<>();
        table.getStyleClass().add("product-dialog-table");
        TableColumn<ProductSnapshot, String> code = new TableColumn<>(message("column.code"));
        code.setCellValueFactory(data -> text(data.getValue().code()));
        code.setPrefWidth(120);
        TableColumn<ProductSnapshot, String> name = new TableColumn<>(message("column.name"));
        name.setCellValueFactory(data -> text(data.getValue().name()));
        name.setPrefWidth(330);
        TableColumn<ProductSnapshot, String> price = new TableColumn<>(message("column.price"));
        price.setCellValueFactory(data -> text(money.format(data.getValue().salePrice())));
        price.setPrefWidth(120);
        table.getColumns().setAll(List.of(code, name, price));

        Runnable refreshList = () -> {
            table.setItems(FXCollections.observableArrayList(catalog().search(searchField.getText())));
            if (!table.getItems().isEmpty()) {
                table.getSelectionModel().selectFirst();
            }
        };
        searchField.textProperty().addListener((ignored, oldValue, newValue) -> refreshList.run());
        table.addEventFilter(KeyEvent.KEY_PRESSED, keyEvent -> {
            if (keyEvent.getCode() == KeyCode.INSERT || keyEvent.getCode() == KeyCode.ENTER) {
                ProductSnapshot selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    addProduct(selected);
                    refresh();
                    dialog.close();
                }
                keyEvent.consume();
            } else if (keyEvent.getCode() == KeyCode.F7) {
                openProductManagement();
                keyEvent.consume();
            } else if (keyEvent.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                keyEvent.consume();
            }
        });
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, keyEvent -> {
            if (keyEvent.getCode() == KeyCode.DOWN) {
                table.requestFocus();
                table.getSelectionModel().selectFirst();
                keyEvent.consume();
            } else if (keyEvent.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                keyEvent.consume();
            }
        });

        refreshList.run();
        BorderPane pane = new BorderPane(table);
        pane.setTop(new VBox(searchField));
        pane.getStyleClass().add("product-dialog");
        javafx.scene.Scene scene = new javafx.scene.Scene(pane, 640, 430);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        dialog.setScene(scene);
        dialog.setOnShown(event -> searchField.requestFocus());
        dialog.setOnHidden(event -> quickField.requestFocus());
        dialog.show();
    }

    private void openProductManagement() {
        if (!PermissionRules.canManageProduct(session.permissions())) {
            return;
        }
        ProductManagementWindow.show(
                quickField.getScene().getWindow(),
                localProducts,
                resources,
                money,
                AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm(),
                quickField::requestFocus);
    }

    private ProductCatalog catalog() {
        return new ProductCatalog(localProducts);
    }

    private void parkOrRecover() {
        if (sale.lines().isEmpty()) {
            openParkedSalesDialog();
            return;
        }
        parkedSales.add(new ParkedSale(nextParkedSaleId++, LocalDateTime.now(), session.userName(), List.copyOf(sale.lines())));
        sale.clear();
        status(message("status.saleParked"));
        refresh();
    }

    private void openParkedSalesDialog() {
        if (parkedSales.isEmpty()) {
            showInfo(message("dialog.parked.title"), message("dialog.parked.empty"));
            return;
        }
        Stage dialog = new Stage();
        dialog.setTitle(message("dialog.parked.title"));
        dialog.initOwner(quickField.getScene().getWindow());
        dialog.initModality(Modality.NONE);

        TableView<ParkedSale> table = new TableView<>(parkedSales);
        table.getStyleClass().add("product-dialog-table");
        TableColumn<ParkedSale, String> id = new TableColumn<>(message("parked.column.id"));
        id.setCellValueFactory(data -> text(Integer.toString(data.getValue().id())));
        id.setPrefWidth(80);
        TableColumn<ParkedSale, String> date = new TableColumn<>(message("parked.column.date"));
        date.setCellValueFactory(data -> text(dateTimeFormat.format(data.getValue().parkedAt())));
        date.setPrefWidth(150);
        TableColumn<ParkedSale, String> user = new TableColumn<>(message("parked.column.user"));
        user.setCellValueFactory(data -> text(data.getValue().userName()));
        user.setPrefWidth(160);
        TableColumn<ParkedSale, String> lines = new TableColumn<>(message("parked.column.lines"));
        lines.setCellValueFactory(data -> text(Integer.toString(data.getValue().lines().size())));
        lines.setPrefWidth(90);
        TableColumn<ParkedSale, String> total = new TableColumn<>(message("parked.column.total"));
        total.setCellValueFactory(data -> text(money.format(data.getValue().total())));
        total.setPrefWidth(140);
        table.getColumns().setAll(List.of(id, date, user, lines, total));
        table.getSelectionModel().selectFirst();
        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.INSERT) {
                recoverParkedSale(table.getSelectionModel().getSelectedItem());
                dialog.close();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                event.consume();
            }
        });

        BorderPane pane = new BorderPane(table);
        if (PermissionRules.canClearParkedSales(session.permissions())) {
            Button clear = new Button(message("parked.clear"));
            clear.setOnAction(event -> {
                if (confirm(message("parked.clear.confirm.title"), message("parked.clear.confirm.message"))) {
                    parkedSales.clear();
                    dialog.close();
                    status(message("status.parkedSalesCleared"));
                }
            });
            HBox actions = new HBox(clear);
            actions.getStyleClass().add("parked-actions");
            pane.setBottom(actions);
        }
        pane.getStyleClass().add("product-dialog");
        javafx.scene.Scene scene = new javafx.scene.Scene(pane, 660, 360);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        dialog.setScene(scene);
        dialog.setOnShown(event -> table.requestFocus());
        dialog.setOnHidden(event -> quickField.requestFocus());
        dialog.show();
    }

    private void recoverParkedSale(ParkedSale parkedSale) {
        if (parkedSale == null) {
            return;
        }
        sale.replaceLines(parkedSale.lines());
        parkedSales.remove(parkedSale);
        status(message("status.saleRecovered", parkedSale.id()));
        refresh();
    }

    private void openDocumentWindow() {
        if (documentStage != null && documentStage.isShowing()) {
            documentStage.requestFocus();
            return;
        }
        documentStage = new Stage();
        documentStage.setTitle(message("document.window.title"));
        documentStage.initModality(Modality.NONE);
        var label = new Label(message("document.placeholder"));
        label.getStyleClass().add("document-placeholder");
        var scene = new javafx.scene.Scene(label, 520, 260);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        documentStage.setScene(scene);
        documentStage.show();
    }

    private void configureTable() {
        codeColumn.setCellValueFactory(data -> text(data.getValue().code()));
        nameColumn.setCellValueFactory(data -> text(data.getValue().name()));
        quantityColumn.setCellValueFactory(data -> text(data.getValue().quantity().toPlainString()));
        packagesColumn.setCellValueFactory(data -> text(data.getValue().packages().toPlainString()));
        priceColumn.setCellValueFactory(data -> text(money.format(data.getValue().price())));
        discountColumn.setCellValueFactory(data -> text(data.getValue().discountPercent().toPlainString()));
        totalColumn.setCellValueFactory(data -> text(money.format(data.getValue().totalAfterDiscount())));
    }

    private void refresh() {
        lineTable.setItems(FXCollections.observableArrayList(sale.lines()));
        if (sale.selectedIndex() >= 0 && sale.selectedIndex() < lineTable.getItems().size()) {
            lineTable.getSelectionModel().select(sale.selectedIndex());
        }
        bigTotalLabel.setText(money.format(sale.totalAfterDiscount()));
        lineCountLabel.setText(Integer.toString(sale.lines().size()));
        quantitySumLabel.setText(sale.totalQuantity().toPlainString());
        packageSumLabel.setText(sale.totalPackages().toPlainString());
        beforeDiscountLabel.setText(money.format(sale.totalBeforeDiscount()));
        afterDiscountLabel.setText(money.format(sale.totalAfterDiscount()));
        quickField.requestFocus();
    }

    private void status(String text) {
        statusLabel.setText(text);
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
        quickField.requestFocus();
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private SimpleStringProperty text(String text) {
        return new SimpleStringProperty(text);
    }

    private String message(String key, Object... args) {
        String pattern = resources.getString(key);
        return args.length == 0 ? pattern : String.format(pattern, args);
    }

    private static List<ProductSnapshot> sampleProducts() {
        return List.of(
                new ProductSnapshot("20", "8410000000200", "Articulo Mostrador", new BigDecimal("3.50"), 12,
                        "General", "Mostrador"),
                new ProductSnapshot("100", "8410000000100", "Producto Caja", new BigDecimal("9.95"), 6,
                        "General", "Caja"),
                new ProductSnapshot("200", "8410000000201", "Pack Venta", new BigDecimal("1.20"), 24,
                        "Bebidas", "Pack")
        );
    }

    private record ParkedSale(int id, LocalDateTime parkedAt, String userName, List<SaleLine> lines) {

        private BigDecimal total() {
            return lines.stream()
                    .map(SaleLine::totalAfterDiscount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private record PaymentLine(String method, BigDecimal amount, String documentNo, String comment) {
    }
}
