# autofill-parser

Vendored from [Password Store](https://github.com/agrahn/Android-Password-Store/tree/main/autofill-parser), unchanged. It reads an Android `AssistStructure` into a fillable login form and decides which browsers are trusted to report the page's origin.

License: LGPL-3.0-only with the LGPL-3.0 linking exception, as upstream. `src/main/assets/publicsuffixes` is generated from the [Public Suffix List](https://publicsuffix.org) (MPL-2.0) by upstream's `updatePSL` task.

To update, copy upstream's `autofill-parser/src` over this one. Do not edit files here; local changes would be lost on the next copy.
