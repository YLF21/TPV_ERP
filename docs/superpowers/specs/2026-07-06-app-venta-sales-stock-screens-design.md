# APP VENTA Sales And Stock Screens Design

## Goal

Create first-pass Venta and Stock screens in APP VENTA using the current SalesReportScreen visual system.

## Scope

- Add a `SaleScreen` opened from the home `VENTA` button.
- Add a `StockScreen` opened from the home `STOCK` button.
- Both screens use the shared top controls and bottom context footer.
- Login remains without user controls.
- The first pass is UI/navigation only. It does not create orders, modify stock, or call backend APIs.

## Sale Screen

The screen is an operator workspace for a future POS flow. It shows:

- Header with app brand and title.
- A ticket panel with sample line rows and totals.
- A product/search panel with quick actions.
- A payment panel with cash/card/customer pending actions as disabled or inert UI placeholders.

## Stock Screen

The screen is a compact inventory workspace. It shows:

- Header with app brand and title.
- Search and stock status filters.
- Product rows with stock, minimum stock, location, and status.
- Action buttons for future stock adjustment flows.

## Navigation

- Home `VENTA` opens `SaleScreen`.
- Home `STOCK` opens `StockScreen`.
- Both screens return to home using the brand/back button.

## Testing

Use React static render tests for each screen:

- `SaleScreen` renders title, shared controls, footer, and key panels.
- `StockScreen` renders title, shared controls, footer, and key panels.
- `SessionHomeScreen` exposes a callback for stock like it already does for sale.
