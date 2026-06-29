package com.tpverp.frontend.venta;

import com.tpverp.frontend.common.sales.ProductSnapshot;
import com.tpverp.frontend.common.sales.QuickCommand;
import com.tpverp.frontend.common.sales.SaleLine;
import com.tpverp.frontend.common.sales.TicketSale;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

public class AppVentaController {

    private final TicketSale sale = new TicketSale();
    private final Map<String, ProductSnapshot> products = sampleProducts();
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.of("es", "ES"));

    private Stage documentStage;

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

    @FXML
    private void charge() {
        showInfo(message("dialog.charge.title"),
                message("dialog.charge.message", money.format(sale.totalAfterDiscount()), money.format(BigDecimal.ZERO)));
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
        ProductSnapshot product = products.get(code.trim());
        if (product == null) {
            status(message("status.productNotFound", code));
            return;
        }
        sale.addLine(product);
        status(message("status.productAdded", product.code()));
    }

    private void parkOrRecover() {
        if (sale.lines().isEmpty()) {
            showInfo(message("dialog.parked.title"), message("dialog.parked.empty"));
            return;
        }
        sale.clear();
        status(message("status.saleParked"));
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

    private SimpleStringProperty text(String text) {
        return new SimpleStringProperty(text);
    }

    private String message(String key, Object... args) {
        String pattern = resources.getString(key);
        return args.length == 0 ? pattern : String.format(pattern, args);
    }

    private static Map<String, ProductSnapshot> sampleProducts() {
        var map = new LinkedHashMap<String, ProductSnapshot>();
        List.of(
                new ProductSnapshot("20", "Articulo Mostrador", new BigDecimal("3.50"), 12),
                new ProductSnapshot("100", "Producto Caja", new BigDecimal("9.95"), 6),
                new ProductSnapshot("200", "Pack Venta", new BigDecimal("1.20"), 24)
        ).forEach(product -> map.put(product.code(), product));
        return map;
    }
}
