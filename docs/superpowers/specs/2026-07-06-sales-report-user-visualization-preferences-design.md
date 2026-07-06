# Sales Report User Visualization Preferences Design

## Context

APP VENTA already lets the user configure report visualization from
`SalesReportScreen`: visible columns can be added, removed, and reordered per
report. Today that state is only held in React memory, so it is lost when the
screen or app is reopened.

The required behavior is user-scoped persistence across terminals. If the same
user configures a report view on terminal 1, the same view must be loaded when
that user opens APP VENTA on terminal 2 or terminal 3. Terminal identity must
not be part of the preference key.

## Scope

This design covers report visualization preferences for sales reports:

- visible report attributes;
- attribute order;
- one saved configuration per user, app, and report key;
- automatic saving when the user changes visualization;
- loading saved preferences when opening `SalesReportScreen`.

This design does not cover:

- report filters;
- date ranges;
- selected row;
- language;
- print/export defaults;
- terminal-local hardware configuration.

## Persistence Model

The backend is the source of truth because the preference must follow the user
between terminals.

Create a table for user report visualization preferences with these logical
fields:

- `id`
- `user_name`
- `app`
- `report_key`
- `visible_attributes`
- `created_at`
- `updated_at`

There must be a unique constraint on:

- `user_name`
- `app`
- `report_key`

`visible_attributes` stores the ordered list of visible attribute keys as JSON.
The backend treats it as structured data and validates it as a non-empty list of
strings. APP VENTA remains responsible for filtering out attributes that no
longer exist in the current frontend report definition.

## API

Add authenticated endpoints under a sales-report preference path:

- `GET /api/sales-reports/visualization-preferences?app=venta`
- `PUT /api/sales-reports/visualization-preferences/{reportKey}`

The authenticated user is taken from the bearer session. The request must not
accept a `userName` field from the frontend.

The GET response returns all preferences for the current user and app:

```json
{
  "preferences": [
    {
      "reportKey": "salesReport.tickets",
      "visibleAttributes": ["date", "ticket", "user", "total"]
    }
  ]
}
```

The PUT request replaces one report preference for the current user and app:

```json
{
  "app": "venta",
  "visibleAttributes": ["date", "ticket", "user", "total"]
}
```

The PUT operation is an upsert. Saving the same report again updates
`visible_attributes` and `updated_at`.

## Frontend Behavior

When `SalesReportScreen` opens:

1. It initializes with built-in default columns.
2. It requests the current user's backend preferences.
3. For each saved report preference, it sanitizes the saved attributes against
   the report's available attributes.
4. If the sanitized list is usable, it replaces the default for that report.
5. If a saved preference is empty or invalid after sanitizing, the default
   columns remain in use.

When the user adds, removes, or reorders a column:

1. The screen updates immediately.
2. The new ordered list is sent to the backend for the current report.
3. If the save fails, the UI keeps the user's current in-memory choice and logs
   the failure for development visibility. The next fresh start will fall back
   to the last backend-saved preference.

No terminal code is included in the storage key or API request.

## Validation Rules

Frontend sanitization:

- drop attributes that are not available for the report;
- remove duplicates while preserving order;
- keep locked required attributes such as `total` when the report defines them;
- fall back to default visible attributes if no valid attributes remain.

Backend validation:

- `app` must be a known app value;
- `reportKey` must be non-blank;
- `visibleAttributes` must be a non-empty list;
- every attribute entry must be non-blank.

The backend does not need to know every frontend report attribute key in this
phase. That keeps the backend decoupled from report UI column definitions.

## Testing

Backend tests:

- loading preferences returns only the authenticated user's rows for the
  requested app;
- saving a preference inserts a new row;
- saving the same user, app, and report again updates the row;
- one user's preference is not returned for another user;
- terminal identity is not part of lookup or uniqueness.

Frontend tests:

- `SalesReportScreen` fetches saved preferences for the current user;
- saved preferences override defaults after sanitization;
- invalid saved attributes are ignored;
- changing visualization triggers an automatic save for the selected report;
- the save request does not include terminal identity.

## Rollout

This is safe to roll out incrementally:

1. Add backend persistence and endpoints.
2. Add frontend loading while preserving default columns if the endpoint fails.
3. Add frontend automatic saving on visualization changes.

Existing users without saved preferences continue to see the built-in defaults.
