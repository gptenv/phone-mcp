"""Bounded token normalization shared by local acceptance clients; never echo secrets."""
import re
_TOKEN = re.compile(r"[A-Za-z0-9_-]{43,128}\Z")

def normalize_bearer_token(value):
    if not isinstance(value, str) or len(value) > 135:
        raise ValueError("Invalid session token format")
    if value.startswith("Bearer "):
        value = value[7:]
    if not _TOKEN.fullmatch(value):
        raise ValueError("Invalid session token format")
    return value
