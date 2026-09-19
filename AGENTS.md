# Working on UML viewer

Keep the upstream source and specification layout. Python support belongs in
`src/uml_viewer_python` and the existing language interfaces under
`src/uml_viewer/python_language`. Keep tests under `spec` and examples generic.
Avoid language-specific logic in the layout and drawing engines.

Use the local commands in README.md to inspect source, regenerate a diagram,
and measure tests in an isolated copy. Ask before sending source to external
services. The CLI itself does not start an AI service.

After a change, run the affected Python tests and `clojure -M:spec` for shared
viewer changes. Install optional quality dependencies for measurement tests.
Preserve the existing Clojure workflow and current upstream features.

Do not edit generated diagram files. Edit the policy, then regenerate.
Keep generated output in ignored `target/`. Do not commit local paths, examined
projects, private reports, credentials, runtime dependencies, or screenshots.
Missing, stale, partial, or failed measurements are not passing results.

Before publishing, inspect every staged file. Keep commits focused so upstream
updates can be incorporated with small, reviewable changes. Push or merge only
when the user requests it.
