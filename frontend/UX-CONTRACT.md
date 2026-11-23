# Console interaction contract

The API client, session context and backend role checks own domain state. Browser appearance belongs to PreferenceProvider. Persist only validated presentation options. System theme follows the device. Reset restores system theme, blue, comfortable density, full motion and expanded navigation.

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Select/Listbox | Native select | Page choices or preference schema | Device-owned popup | Keyboard selection, narrow viewport |
| Form | ValidatedForm and project editor | Existing field constraints and API errors | Project-specific inline feedback | Invalid input retains typed values |
| Scrollbar | Global CSS | Theme tokens | Local horizontal table scroll | Page width holds at 320px |
| Toast | Toast | Completed request response | Dismissible success | Create project with fixture API |
| CRUD | Existing route components | AuthContext and services/api.ts | Role-gated actions | Existing request and deletion tests |

Dialogs and the mobile drawer lock document scroll, contain Tab navigation, support Escape and return focus. The desktop rail retains link names in icon mode. Tables retain captions and server pagination where provided. A failed probe, a loading probe and a successful probe have distinct text. Release and pipeline views show planned capabilities until the backend supplies execution data.
