# Origin

EDT-Bridge is an independent, **clean-room** implementation, written from scratch.

It is built only from:

- the open **Model Context Protocol (MCP)** specification (JSON-RPC 2.0 shape), and
- **1C:EDT's own public Eclipse plugin APIs** – standard Eclipse/OSGi plugin development
  (`org.eclipse.*`, `com._1c.g5.v8.dt.*`).

No other tool's source code was read or copied. The tool names (`edt_project_errors`,
`edt_metadata_details`, ...) are plain functional descriptions of their capabilities, not
derived from any other project.

At runtime the plugin uses Google Gson (Apache-2.0); all other building blocks (Eclipse
Platform, Xtext, the 1C:EDT model APIs) are provided by the host EDT installation.
