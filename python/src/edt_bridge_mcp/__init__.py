"""edt-bridge-mcp: stdio MCP front-end for the edt-bridge 1C:EDT plugin."""

# The single source of truth for the wrapper version: pyproject.toml derives the package version
# from this attribute, so the two cannot drift. They did once - the wrapper went on reporting 0.3.1
# for two releases, in --version and in the MCP serverInfo alike. Bump it with the plugin jar; the
# two share one version number.
__version__ = "0.5.0"
