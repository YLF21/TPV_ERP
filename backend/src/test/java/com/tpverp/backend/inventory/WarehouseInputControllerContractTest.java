package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

class WarehouseInputControllerContractTest {

    @Test
    void exposesWarehouseInputApiWithMethodSecurity() {
        assertThat(WarehouseInputController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/warehouse-inputs");
        assertThat(Arrays.stream(WarehouseInputController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .hasSize(5);
    }

    @Test
    void exposesListCreateUpdateDeleteAndConfirmEndpoints() throws NoSuchMethodException {
        var list = WarehouseInputController.class.getDeclaredMethod("list");
        var create = WarehouseInputController.class.getDeclaredMethod(
                "create", WarehouseInputCommand.class, org.springframework.security.core.Authentication.class);
        var update = WarehouseInputController.class.getDeclaredMethod(
                "update", UUID.class, WarehouseInputCommand.class);
        var delete = WarehouseInputController.class.getDeclaredMethod("delete", UUID.class);
        var confirm = WarehouseInputController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class);

        assertThat(list.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(list.getGenericReturnType().getTypeName())
                .contains("java.util.List", "WarehouseInputView");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(create.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.CREATED);
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{id}");
        assertThat(delete.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(confirm.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/confirm");
        assertThat(Arrays.stream(update.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(PathVariable.class)))
                .hasSize(1);
        assertThat(list.getAnnotation(PreAuthorize.class).value())
                .contains(
                        "WAREHOUSE_INPUTS_READ",
                        "WAREHOUSE_INPUTS_WRITE",
                        "WAREHOUSE_INPUTS_DELETE",
                        "WAREHOUSE_INPUTS_CONFIRM",
                        "GESTION_PRODUCTO",
                        "hasRole('ADMIN')");
        assertThat(create.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSE_INPUTS_WRITE", "GESTION_PRODUCTO", "hasRole('ADMIN')");
        assertThat(update.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSE_INPUTS_WRITE", "GESTION_PRODUCTO", "hasRole('ADMIN')");
        assertThat(delete.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSE_INPUTS_DELETE", "GESTION_PRODUCTO", "hasRole('ADMIN')");
        assertThat(confirm.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSE_INPUTS_CONFIRM", "GESTION_PRODUCTO", "hasRole('ADMIN')");
    }
}
