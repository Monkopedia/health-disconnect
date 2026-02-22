# TODO

- [x] Increase bottom padding under the entries row in the data view before the divider.
- [x] Fix time window restore: it defaulted to 30 days even though the saved view was 1 year.
- [x] Move the granting history explanation in Settings above the Advanced row.
- [x] Redesign Settings header to use a Material-styled header bar with the title inside it and a back arrow icon (instead of back text).
- [x] Make the entries list use the same units selected for the data view.
- [x] Fix rapid expand/collapse clicks causing animation/state desync (expanded indicator mismatches row state); centralize behavior in a reusable expand/collapse widget and prevent bad states via click gating or animation queueing.
- [x] We show max under a graph for all items but not min; make both min and max optional under controls for a specific metric in view configuration.
- [ ] Build widget support for this app. If there are no views, widget should say that. Otherwise widget add/config should select an existing view, and widget should display the graph and labels under it (including min/max), but not the share button.
- [ ] When a widget is enabled for a view, support a view configuration option selecting widget update frequency (start with 3h/6h/12h/24h).
- [ ] When adding a widget and selecting the linked view, also allow selecting the update window at that time.
- [ ] Build a JobScheduler background job which updates widgets at the requested interval, pulling data from Health Connect.
- [ ] Clicking a widget should deep link into the app and open the full app data view for the linked widget.
- [ ] Change graph share button behavior to open a bottom sheet with options: share graph, share entries (same as entries screen), and add a widget to the home screen (initiating that flow).
- [ ] Add extensive tests for the above and then do a cleanup pass for architecture/code quality and close any testing gaps that can be fixed.
