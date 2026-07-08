# Stock Top Sales Design

## Goal

Add `Top ventas` to APP VENTA `StockScreen` so the user can see the best-selling products by net units for a selected moving period.

## Scope

- Add `Top ventas` as a stock view inside `StockScreen`.
- `StockScreen` opens `Top ventas` by default.
- The default period is `Semana`.
- The default date is the current local date.
- Backend calculates the complete ranking for the selected date and period.
- Frontend applies all visible filters locally after receiving the backend result.

This design does not add stock adjustment, purchase suggestions, exports, printing, or charting.

## Periods

The period selector uses moving ranges counted backwards from the selected date:

- `Dia`: only the selected date.
- `Semana`: selected date plus the previous 6 days, 7 days total.
- `Mes`: selected date plus the previous 29 days, 30 days total.
- `Ano`: selected date plus the previous 364 days, 365 days total.

Examples:

- If date is `2026-07-08` and period is `Semana`, the range is `2026-07-02` through `2026-07-08`.
- If date is a day in February of last year and period is `Mes`, the range is the 30-day window ending on that February date.

## Backend Contract

Add an authenticated stock endpoint:

```http
GET /api/v1/stock/top-sales?period=week&date=2026-07-08
```

`period` accepts:

- `day`
- `week`
- `month`
- `year`

`date` is required and uses ISO local date format.

The backend receives only date and period. It does not receive family, subfamily, supplier, or text filters in this phase.

## Backend Calculation

The backend calculates the full ranking for the current store and selected range.

It includes sale document lines from non-draft, non-cancelled commercial sale documents:

- tickets
- sale delivery notes
- sale invoices
- sale corrective invoices

The ranking is ordered by net sold units descending.

Returns and negative sale lines subtract from sold units. The backend returns products whose net sold units are greater than zero. Products that end the selected range at zero or negative net units are excluded from `Top ventas` because they are not best-selling products for that period.

## Response Shape

Each result row should include enough product context for frontend-only filtering:

```json
{
  "productId": "uuid",
  "code": "A001",
  "barcode": "8430000000011",
  "name": "Cafe molido",
  "familyId": "uuid",
  "familyName": "Alimentacion",
  "subfamilyId": "uuid",
  "subfamilyName": "Cafe",
  "suppliers": [
    {
      "supplierId": "uuid",
      "supplierCode": "PR0001",
      "supplierName": "Proveedor General"
    }
  ],
  "soldQuantity": 82.000,
  "netAmount": 246.00,
  "currentStock": 14.000,
  "warehouseId": "uuid",
  "warehouseName": "GENERAL"
}
```

Products with multiple suppliers match any of their suppliers in frontend filters.

## Frontend Behavior

`StockScreen` adds a `Top ventas` side navigation item and selects it by default.

The top-sales workspace includes:

- period selector: `Dia`, `Semana`, `Mes`, `Ano`;
- date input for the period end date;
- local filters for family, subfamily, and supplier;
- existing text search if it remains useful for code, barcode, name, family, subfamily, supplier, and warehouse.

Changing period or date reloads backend data.

Changing family, subfamily, supplier, or text search filters the current backend result locally without another request.

The displayed ranking keeps sold units as the primary order after filters are applied.

## UI Columns

Use a compact operational table inside the existing `StockScreen` visual language:

- ranking position
- code
- barcode
- product name
- family
- subfamily
- supplier
- sold units
- net amount
- current stock
- warehouse

The primary emphasis is sold units. Net amount and stock are secondary context for replenishment decisions.

## Empty And Error States

- If the backend returns no rows for the selected period, show `Sin ventas en el periodo`.
- If local filters remove all rows, show `Sin resultados`.
- If the backend request fails, keep the current `StockScreen` error pattern and show the error in the data panel.

## Testing

Backend tests should cover:

- period range calculation for day, week, month, and year;
- sale documents included and draft/cancelled documents excluded;
- returns or negative lines subtracting units;
- sorting by net sold units descending;
- response includes family, subfamily, supplier, stock, and warehouse data needed by the frontend.

Frontend tests should cover:

- `StockScreen` opens on `Top ventas` by default;
- period/date changes call the backend with only period and date;
- family, subfamily, supplier, and text filters run locally;
- filtered ranking remains ordered by sold units;
- empty states distinguish no backend rows from no local filter results.
