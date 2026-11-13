from edt_bridge_mcp import scope, server


def _environment(tmp_path, **extra):
    values = {scope.WRITE_ENV: "Own", scope.READ_ENV: "Base, Own", scope.OUTPUT_ENV: str(tmp_path)}
    values.update(extra)
    return values


def _status(tmp_path, **override):
    reported = {"active": True, "writeProjects": ["Own"], "readProjects": ["Base"], "outputDir": str(tmp_path)}
    reported.update(override)
    return {"scope": reported}


def test_without_settings_the_wrapper_has_no_scope_and_forwards_anything():
    wrapper = scope.from_environment({})
    assert not wrapper.active
    assert scope.mismatch(wrapper, {"scope": {"active": False}}) is None
    assert scope.mismatch(wrapper, None) is None


def test_settings_are_parsed_as_the_bridge_parses_them(tmp_path):
    wrapper = scope.from_environment(_environment(tmp_path))
    assert wrapper.write == {"Own"}
    assert wrapper.read == {"Base"}, "a writable project is not also read-only"
    assert wrapper.projects_limited and wrapper.active


def test_the_same_scope_on_the_bridge_lets_calls_through(tmp_path):
    wrapper = scope.from_environment(_environment(tmp_path))
    # The bridge reports the directory in its own spelling; a trailing separator does not matter.
    status = _status(tmp_path, outputDir=str(tmp_path) + "/")
    assert scope.mismatch(wrapper, status) is None


def test_a_bridge_without_the_scope_is_refused_with_both_sides_named(tmp_path):
    wrapper = scope.from_environment(_environment(tmp_path))
    for status in (None, {}, {"scope": {"active": False}}, _status(tmp_path, writeProjects=["Other"])):
        problem = scope.mismatch(wrapper, status)
        assert problem is not None, status
        assert "Own" in problem and scope.WRITE_ENV in problem and "edt-bridge-mcp shutdown" in problem


def test_a_bridge_with_another_output_directory_is_refused(tmp_path):
    wrapper = scope.from_environment(_environment(tmp_path))
    assert scope.mismatch(wrapper, _status(tmp_path, outputDir=str(tmp_path / "other"))) is not None


def test_the_wrapper_refuses_a_bridge_call_while_the_scopes_differ(tmp_path, monkeypatch):
    for name, value in _environment(tmp_path).items():
        monkeypatch.setenv(name, value)
    srv = server.StdioServer(server.Backend())
    sent = []
    forwarded = []
    monkeypatch.setattr(srv, "_send", sent.append)
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "status", lambda: {"scope": {"active": False}})
    monkeypatch.setattr(srv, "_forward_or_error", lambda message, req_id: forwarded.append(message))
    call = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "edt_metadata_details", "arguments": {"projectName": "Own", "fqn": "Catalog.X"}}}
    srv.handle(call)
    assert not forwarded
    assert sent[0]["result"]["isError"] is True
    assert "different write scope" in sent[0]["result"]["content"][0]["text"]
    # Once the bridge holds the same scope the call goes through; the verdict is renewed after a while.
    monkeypatch.setattr(srv.backend, "status", lambda: _status(tmp_path))
    srv._scope_checked = None
    srv.handle(call)
    assert forwarded == [call]
