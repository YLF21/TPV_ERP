package com.tpverp.frontend.common.ui;

import com.tpverp.frontend.common.sales.ProductSnapshot;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ProductManagementWindow {

    private final ObservableList<ProductSnapshot> products;
    private final ResourceBundle resources;
    private final NumberFormat money;

    private ProductManagementWindow(ObservableList<ProductSnapshot> products,
                                    ResourceBundle resources,
                                    NumberFormat money) {
        this.products = products;
        this.resources = resources;
        this.money = money;
    }

    public static void show(Window owner,
                            ObservableList<ProductSnapshot> products,
                            ResourceBundle resources,
                            NumberFormat money,
                            String stylesheet,
                            Runnable onHidden) {
        new ProductManagementWindow(products, resources, money).show(owner, stylesheet, onHidden);
    }

    private void show(Window owner, String stylesheet, Runnable onHidden) {
        Stage stage = new Stage();
        stage.setTitle(message("product.management.title"));
        stage.initOwner(owner);
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
        Runnable refreshTable = () -> table.setItems(FXCollections.observableArrayList(products.stream()
                .filter(product -> matchesFilter(product.family(), familyFilter.getValue()))
                .filter(product -> matchesFilter(product.subfamily(), subfamilyFilter.getValue()))
                .toList()));
        familyFilter.valueProperty().addListener((ignored, oldValue, newValue) -> refreshTable.run());
        subfamilyFilter.valueProperty().addListener((ignored, oldValue, newValue) -> refreshTable.run());

        Button add = new Button(message("product.management.add"));
        add.setOnAction(event -> {
            ProductSnapshot created = editProduct(null);
            if (created != null) {
                products.add(created);
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
                products.set(products.indexOf(selected), edited);
                refreshFilters.run();
                refreshTable.run();
            }
        });
        Button delete = new Button(message("product.management.delete"));
        delete.setOnAction(event -> {
            ProductSnapshot selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                products.remove(selected);
                refreshFilters.run();
                refreshTable.run();
            }
        });

        VBox content = new VBox(10, new HBox(10, familyFilter, subfamilyFilter),
                table, new HBox(10, add, edit, delete));
        content.getStyleClass().add("product-dialog");
        refreshFilters.run();
        refreshTable.run();

        Scene scene = new Scene(content, 860, 560);
        if (stylesheet != null && !stylesheet.isBlank()) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setOnHidden(event -> onHidden.run());
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

    private List<String> values(Function<ProductSnapshot, String> extractor) {
        return Stream.concat(Stream.of(""), products.stream().map(extractor))
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private boolean matchesFilter(String value, String filter) {
        return filter == null || filter.isBlank() || filter.equals(value);
    }

    private SimpleStringProperty text(String text) {
        return new SimpleStringProperty(text);
    }

    private String message(String key) {
        return resources.getString(key);
    }
}
