UPDATE metodo_pago
SET requiere_referencia = false
WHERE nombre = 'VALE'
  AND requiere_referencia = true;
