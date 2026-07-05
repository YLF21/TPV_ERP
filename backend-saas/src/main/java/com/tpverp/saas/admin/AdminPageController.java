package com.tpverp.saas.admin;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminPageController {

    @GetMapping(value = {"/", "/admin"}, produces = MediaType.TEXT_HTML_VALUE)
    String admin() {
        return """
                <!doctype html>
                <html lang="es">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>TPV ERP SaaS Admin</title>
                    <style>
                        :root { color-scheme: light; font-family: Inter, Segoe UI, Arial, sans-serif; }
                        body { margin: 0; background: #f6f7f9; color: #1d2430; }
                        main { max-width: 980px; margin: 0 auto; padding: 32px; }
                        h1 { font-size: 28px; margin: 0 0 8px; }
                        p { color: #5b6575; margin: 0 0 24px; }
                        form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; background: white; border: 1px solid #d9dee7; border-radius: 8px; padding: 20px; }
                        label { display: grid; gap: 6px; font-size: 13px; font-weight: 600; color: #3b4452; }
                        input, select { box-sizing: border-box; width: 100%; border: 1px solid #c8cfdb; border-radius: 6px; padding: 10px 12px; font: inherit; background: white; }
                        .wide { grid-column: 1 / -1; }
                        button { justify-self: start; border: 0; border-radius: 6px; padding: 11px 16px; background: #185abc; color: white; font-weight: 700; cursor: pointer; }
                        button:disabled { opacity: .6; cursor: wait; }
                        pre { margin-top: 18px; white-space: pre-wrap; background: #101828; color: #d9e7ff; border-radius: 8px; padding: 16px; min-height: 72px; overflow: auto; }
                        .error { color: #ffd6d6; }
                        @media (max-width: 760px) { main { padding: 20px; } form { grid-template-columns: 1fr; } }
                    </style>
                </head>
                <body>
                    <main>
                        <h1>TPV ERP SaaS Admin</h1>
                        <p>Crea una empresa, tienda, licencia y codigo de enlace para la instalacion local.</p>
                        <form id="companyForm">
                            <label class="wide">Admin key
                                <input name="adminKey" value="dev-admin-key" autocomplete="off">
                            </label>
                            <label>Nombre empresa
                                <input name="name" value="Empresa Demo" required>
                            </label>
                            <label>NIF/CIF
                                <input name="taxId" value="B00000000" required>
                            </label>
                            <label>Tipo contribuyente
                                <select name="taxpayerType"><option>SOCIEDAD</option><option>AUTONOMO</option></select>
                            </label>
                            <label>Impuestos
                                <select name="impuestos"><option>IVA</option><option>IGIC</option></select>
                            </label>
                            <label>Codigo tienda
                                <input name="storeCode" value="TIENDA01" required>
                            </label>
                            <label>Nombre tienda
                                <input name="storeName" value="Tienda principal">
                            </label>
                            <label>Valida hasta
                                <input name="validUntil" type="datetime-local" required>
                            </label>
                            <label>Windows
                                <input name="maxWindows" type="number" min="1" value="1" required>
                            </label>
                            <label>PDA
                                <input name="maxPda" type="number" min="0" value="0" required>
                            </label>
                            <div class="wide"><button type="submit">Crear empresa y licencia</button></div>
                        </form>
                        <pre id="result">Listo para crear licencia.</pre>
                    </main>
                    <script>
                        const form = document.getElementById('companyForm');
                        const result = document.getElementById('result');
                        const validUntil = form.elements.validUntil;
                        const nextYear = new Date();
                        nextYear.setFullYear(nextYear.getFullYear() + 1);
                        validUntil.value = nextYear.toISOString().slice(0, 16);
                        form.addEventListener('submit', async (event) => {
                            event.preventDefault();
                            const button = form.querySelector('button');
                            button.disabled = true;
                            result.className = '';
                            result.textContent = 'Enviando...';
                            const values = Object.fromEntries(new FormData(form).entries());
                            const payload = {
                                name: values.name,
                                taxId: values.taxId,
                                taxpayerType: values.taxpayerType,
                                impuestos: values.impuestos,
                                storeCode: values.storeCode,
                                storeName: values.storeName,
                                validUntil: new Date(values.validUntil).toISOString(),
                                maxWindows: Number(values.maxWindows),
                                maxPda: Number(values.maxPda)
                            };
                            try {
                                const response = await fetch('/api/v1/admin/companies', {
                                    method: 'POST',
                                    headers: {
                                        'Content-Type': 'application/json',
                                        'X-TPV-SaaS-Admin-Key': values.adminKey
                                    },
                                    body: JSON.stringify(payload)
                                });
                                const text = await response.text();
                                if (!response.ok) throw new Error(text || response.statusText);
                                const data = JSON.parse(text);
                                result.textContent = JSON.stringify(data, null, 2);
                            } catch (error) {
                                result.className = 'error';
                                result.textContent = error.message;
                            } finally {
                                button.disabled = false;
                            }
                        });
                    </script>
                </body>
                </html>
                """;
    }
}
