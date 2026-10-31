"""Deterministic pinned-SDK tests; MockTransport only, no Android or network listener."""
import contextlib
import errno
import io
import json
import logging
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx2
import sdk_client as target

TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
SECRET = "DO_NOT_LOG_LOCATION_" + TOKEN
REAL_CLIENT = httpx2.AsyncClient

class AcceptanceClientTests(unittest.TestCase):
    def setUp(self):
        old_disable = logging.root.manager.disable
        self.addCleanup(logging.disable, old_disable)

    def invoke(self, handler, extra=(), token=TOKEN, old=None):
        seen=[]
        def transport_handler(request):
            seen.append(request)
            return handler(request)
        def factory(**kwargs):
            self.assertFalse(kwargs.get("trust_env", True))
            self.assertFalse(kwargs.get("follow_redirects", True))
            return REAL_CLIENT(transport=httpx2.MockTransport(transport_handler), **kwargs)
        env={"PHONE_MCP_TOKEN":token}
        if old is not None: env["PHONE_MCP_OLD_TOKEN"]=old
        with tempfile.TemporaryDirectory(prefix="sdk-client-test-") as directory:
            path=Path(directory)/"report.json"
            out, err=io.StringIO(),io.StringIO()
            with patch.dict(os.environ,env,clear=True), patch.object(target.httpx2,"AsyncClient",side_effect=factory), contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                code=target.main(["--output",str(path),*extra])
            output=out.getvalue()+err.getvalue()
            self.assertNotIn(TOKEN,output);self.assertNotIn(SECRET,output)
            self.assertNotIn("Traceback",output);self.assertNotIn("Location:",output)
            report=json.loads(path.read_text()) if path.exists() else json.loads(out.getvalue())
            return code, report, seen

    def test_timeout_eof_and_generic_connect_error_are_inconclusive(self):
        for cls in (httpx2.ReadTimeout,httpx2.ConnectTimeout,httpx2.RemoteProtocolError,httpx2.ReadError,httpx2.ConnectError):
            with self.subTest(error=cls.__name__):
                def handler(request): raise cls(SECRET,request=request)
                code,report,_=self.invoke(handler,["--expect-stopped"])
                self.assertEqual(2,code);self.assertEqual("INCONCLUSIVE",report["result"])
                self.assertEqual("STOP_OBSERVATION_AMBIGUOUS",report["reason"])

    def test_explicit_auth_or_unavailable_status_pass_but_other_status_fails(self):
        for status,expected in ((401,0),(503,0),(200,1),(400,1),(429,1),(500,1)):
            with self.subTest(status=status):
                code,report,_=self.invoke(lambda req:httpx2.Response(status),["--expect-stopped"])
                self.assertEqual(expected,code)
                self.assertEqual("PASS" if expected==0 else "FAIL",report["result"])
                self.assertFalse(report["phone_stop_proven"]);self.assertTrue(report["requires_coordinated_device_evidence"])

    def test_only_os_causal_connection_refusal_passes(self):
        def refused(request):
            error=httpx2.ConnectError(SECRET,request=request)
            error.__cause__=ConnectionRefusedError(errno.ECONNREFUSED,SECRET)
            raise error
        code,report,_=self.invoke(refused,["--expect-stopped"])
        self.assertEqual(0,code);self.assertEqual("CONNECTION_REFUSED",report["stopped_observation"])
        self.assertFalse(report["phone_stop_proven"])
        def unrelated(request):
            error=httpx2.ConnectError("Connection refused "+SECRET,request=request)
            error.__cause__=OSError(errno.ETIMEDOUT,SECRET)
            raise error
        self.assertEqual(2,self.invoke(unrelated,["--expect-stopped"])[0])

    def test_refusal_not_inferred_from_suppressed_context_or_nonconnect_error(self):
        error=httpx2.ConnectError(SECRET)
        error.__context__=ConnectionRefusedError(errno.ECONNREFUSED,SECRET)
        error.__suppress_context__=True
        self.assertFalse(target.concrete_connection_refusal(error))
        error=httpx2.ConnectError(SECRET)
        error.__cause__=ConnectionRefusedError(errno.ETIMEDOUT,SECRET)
        self.assertFalse(target.concrete_connection_refusal(error))
        error=httpx2.ReadError(SECRET)
        error.__cause__=ConnectionRefusedError(errno.ECONNREFUSED,SECRET)
        self.assertFalse(target.concrete_connection_refusal(error))

    def test_all_redirect_statuses_fail_without_following_or_echoing(self):
        for status in (300,301,302,303,304,305,306,307,308,399):
            with self.subTest(status=status):
                code,report,seen=self.invoke(lambda req:httpx2.Response(status,headers={"Location":"http://127.0.0.1:8765/mcp?secret="+SECRET}),["--expect-stopped"])
                self.assertEqual(1,code);self.assertEqual("REDIRECT_REJECTED",report["reason"])
                self.assertEqual(1,len(seen))

    def test_real_sdk_same_origin_redirect_handling_cannot_bypass_hook(self):
        def handler(request):
            if request.method=="GET": return httpx2.Response(405)
            if json.loads(request.content)["method"]=="ping": return httpx2.Response(401)
            return httpx2.Response(307,headers={"Location":"http://127.0.0.1:8765/mcp?secret="+SECRET})
        code,report,seen=self.invoke(handler)
        self.assertEqual(1,code);self.assertEqual("REDIRECT_REJECTED",report["reason"])
        self.assertEqual(3,len(seen)) # auth probe, GET probe, SDK discover; fallback cannot send.
        self.assertTrue(all(str(request.url)=="http://127.0.0.1:8765/mcp" for request in seen))

    def test_exact_url_guard_rejects_userinfo_query_fragment_and_other_spellings(self):
        bad=["http://user:"+TOKEN+"@127.0.0.1:8765/mcp","http://127.0.0.1:8765/mcp?secret="+TOKEN,
             "http://127.0.0.1:8765/mcp#"+TOKEN,"http://localhost:8766/mcp","https://localhost:8765/mcp",
             "http://localhost.evil:8765/mcp","http://127.0.0.1:8765/mcp/","HTTP://localhost:8765/mcp",
             "http://%31%32%37.0.0.1:8765/mcp","http://[::1]:8765/mcp"]
        for url in bad:
            with self.subTest(url_kind=bad.index(url)):
                code,report,seen=self.invoke(lambda req:self.fail("URL must fail before I/O"),["--url",url])
                self.assertEqual(1,code);self.assertEqual("INVALID_URL",report["reason"]);self.assertEqual([],seen)
        for url in target.ALLOWED_URLS: target.validate_url(url)

    def test_argument_parser_never_echoes_unknown_secret_argument(self):
        code,report,seen=self.invoke(lambda req:self.fail("No I/O expected"),["--unknown",SECRET])
        self.assertEqual(1,code);self.assertEqual("INVALID_ARGUMENTS",report["reason"]);self.assertEqual([],seen)

    @staticmethod
    def happy_handler(request):
        if request.headers.get("authorization")!="Bearer "+TOKEN: return httpx2.Response(401)
        if request.method=="GET": return httpx2.Response(405)
        message=json.loads(request.content);method=message["method"]
        if method=="server/discover":return httpx2.Response(400)
        if method=="notifications/initialized":return httpx2.Response(202)
        if method=="initialize": result={"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"test"}}
        elif method=="tools/list": result={"tools":[{"name":name,"inputSchema":{"type":"object"}} for name in ["global_action","node_click","node_scroll","node_set_text","phone_status","screen_inspect"]]}
        elif method=="tools/call": result={"content":[{"type":"text","text":"{\"active\":true}"}],"isError":False}
        else: raise AssertionError("Unexpected fixture method")
        return httpx2.Response(200,json={"jsonrpc":"2.0","id":message["id"],"result":result})

    def test_raw_or_single_bearer_token_and_old_token_rejection_reinitialize(self):
        for token in (TOKEN,"Bearer "+TOKEN):
            code,report,seen=self.invoke(self.happy_handler,token=token,old="Bearer "+TOKEN+"old")
            self.assertEqual(0,code);self.assertEqual("PASS",report["result"])
            self.assertEqual(2,len(report["sessions"]));self.assertEqual(401,report["old_token_status"])
            self.assertTrue(any(req.headers.get("authorization")=="Bearer "+TOKEN for req in seen))
            self.assertFalse(any("Bearer Bearer" in req.headers.get("authorization","") for req in seen))

    def test_duplicate_or_invalid_bearer_prefix_fails_before_io(self):
        for token in ("Bearer Bearer "+TOKEN,"bearer "+TOKEN,TOKEN+"\n"):
            code,report,seen=self.invoke(lambda req:self.fail("No I/O expected"),token=token)
            self.assertEqual(1,code);self.assertEqual("INVALID_TOKEN",report["reason"]);self.assertEqual([],seen)

    def test_top_level_unexpected_failure_is_sanitized_and_secret_label_redacted(self):
        def fail(request): raise RuntimeError("Location: "+SECRET)
        code,report,_=self.invoke(fail,["--expect-stopped"])
        self.assertEqual(1,code);self.assertEqual("CHECK_FAILED",report["reason"])
        code,report,_=self.invoke(lambda request:httpx2.Response(401),["--expect-stopped","--label",TOKEN])
        self.assertEqual(0,code);self.assertEqual("local-endpoint",report["label"])

if __name__=="__main__":unittest.main()
