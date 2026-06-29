package com.tpverp.frontend.venta;

import com.tpverp.frontend.common.sales.ProductCatalog;
import com.tpverp.frontend.common.sales.ProductSnapshot;
import com.tpverp.frontend.common.sales.QuickCommand;
import com.tpverp.frontend.common.sales.SaleLine;
import com.tpverp.frontend.common.sales.TicketSale;
import com.tpverp.frontend.common.security.PermissionRules;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Stream;

public class AppVentaController {

    private final TicketSale sale = new TicketSale();
    private final ObservableList<ProductSnapshot> localProducts = FXCollections.observableArrayList(sampleProducts());
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.of("es", "ES"));

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
        Stage stage = new Stage();
        stage.setTitle(message("product.management.title"));
        stage.initOwner(quickField.getScene().getWindow());
        stage.initModality(Modality.NONE);

        ComboBox<String> familyFilter = new ComboBox<>();
        familyFilter.setPromptText(message("product.management.family"));
        ComboBox<String> subfamilyFilter = new ComboBox<>();
        subfamilyFilter.setPromptText(message("product.management.subfamily"));

        TableView<ProductSnapshot> table = new TableView<>();
        table.getStyleClass().add("product-dialog-table");
        TableColumn<ProductSnapshot, String> code = new TableColumn<>(message("column.code"));
        code.setCellValueFactory(data -> text(data.getValue().code()));
        code.setPrefWidth(115);
        TableColumn<ProductSnapshot, String> name = new TableColumn<>(message("column.name"));
        name.setCellValueFactory(data -> text(data.getValue().name()));
        name.setPrefWidth(260);
        TableColumn<ProductSnapshot, String> family = new TableColumn<>(message("product.management.family"));
        family.setCellValueFactory(data -> text(data.getValue().family()));
        family.setPrefWidth(150);
        TableColumn<ProductSnapshot, String> subfamily = new TableColumn<>(message("product.management.subfamily"));
        subfamily.setCellValueFactory(data -> text(data.getValue().subfamily()));
        subfamily.setPrefWidth(150);
        TableColumn<ProductSnapshot, String> price = new TableColumn<>(message("column.price"));
        price.setCellValueFactory(data -> text(money.format(data.getValue().salePrice())));
        price.setPrefWidth(110);
        table.getColumns().setAll(List.of(code, name, family, subfamily, price));

        Runnable refreshFilters = () -> {
            familyFilter.setItems(FXCollections.observableArrayList(values(ProductSnapshot::family)));
            subfamilyFilter.setItems(FXCollections.observableArrayList(values(ProductSnapshot::subfamily)));
        };
        Runnable refreshTable = () -> table.setItems(FXCollections.observableArrayList(localProducts.stream()
                .filter(product -> matchesFilter(product.family(), familyFilter.getValue()))
                .filter(product -> matchesFilter(product.subfamily(), subfamilyFilter.getValue()))
                .toList()));
        familyFilter.valueProperty().addListener((ignored, oldValue, newValue) -> refreshTable.run());
        subfamilyFilter.valueProperty().addListener((ignored, oldValue, newValue) -> refreshTable.run());

        Button add = new Button(message("product.management.add"));
        add.setOnAction(event -> {
            ProductSnapshot created = editProduct(null);
            if (created != null) {
                localProducts.add(created);
                refreshFilters.run();
                refreshTable.run();
            }
        });
        Button edit = new Button(message("product.management.edit"));
        edit.setOnAction(event -> {
            ProductSnapshot selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            ProductSnapshot edited = editProduct(selected);
            if (edited != null) {
                localProducts.set(localProducts.indexOf(selected), edited);
                refreshFilters.run();
                refreshTable.run();
            }
        });
        Button delete = new Button(message("product.management.delete"));
        delete.setOnAction(event -> {
            ProductSnapshot selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                localProducts.remove(selected);
                refreshFilters.run();
                refreshTable.run();
            }
        });

        VBox content = new VBox(10, new javafx.scene.layout.HBox(10, familyFilter, subfamilyFilter),
                table, new javafx.scene.layout.HBox(10, add, edit, delete));
        content.getStyleClass().add("product-dialog");
        refreshFilters.run();
        refreshTable.run();

        javafx.scene.Scene scene = new javafx.scene.Scene(content, 860, 560);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(event -> quickField.requestFocus());
        stage.show();
    }

    private ProductSnapshot editProduct(ProductSnapshot current) {
        Dialog<ProductSnapshot> dialog = new Dialog<>();
        dialog.setTitle(current == null ? message("product.management.add") : message("product.management.edit"));
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType(message("product.form.save"), ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);

        TextField code = field(current == null ? "" : current.code(), "product.form.code");
        TextField barcode = field(current == null ? "" : current.barcode(), "product.form.barcode");
        TextField name = field(current == null ? "" : current.name(), "product.form.name");
        TextField price = field(current == null ? "0.00" : current.salePrice().toPlainString(), "product.form.price");
        TextField units = field(current == null ? "1" : Integer.toString(current.unitsPerPackage()), "product.form.units");
        TextField family = field(current == null ? "" : current.family(), "product.form.family");
        TextField subfamily = field(current == null ? "" : current.subfamily(), "product.form.subfamily");
        dialog.getDialogPane().setContent(new VBox(8, code, barcode, name, price, units, family, subfamily));
        dialog.setResultConverter(button -> {
            if (button.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return new ProductSnapshot(
                    code.getText(),
                    barcode.getText(),
                    name.getText(),
                    new BigDecimal(price.getText().replace(',', '.')),
                    Integer.parseInt(units.getText()),
                    family.getText(),
                    subfamily.getText());
        });
        return dialog.showAndWait().orElse(null);
    }

    private TextField field(String value, String promptKey) {
        TextField field = new TextField(value);
        field.setPromptText(message(promptKey));
        field.getStyleClass().add("dialog-search-field");
        return field;
    }

    private ProductCatalog catalog() {
        return new ProductCatalog(localProducts);
    }

    private List<String> values(java.util.function.Function<ProductSnapshot, String> extractor) {
        return Stream.concat(Stream.of(""), localProducts.stream().map(extractor))
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private boolean matchesFilter(String value, String filter) {
        return filter == null || filter.isBlank() || filter.equals(value);
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
}
