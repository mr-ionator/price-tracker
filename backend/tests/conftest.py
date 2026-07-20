import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

_tmpdir = tempfile.mkdtemp(prefix="pricetracker-test-")
os.environ["DATABASE_URL"] = f"sqlite:///{_tmpdir}/test.db"
os.environ["CHECK_INTERVAL_MINUTES"] = "9999"
os.environ["SCRAPE_DELAY_SECONDS"] = "0"

from fastapi.testclient import TestClient  # noqa: E402

from app.database import Base, engine  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture()
def client():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    # Avoid the lifespan starting the real scheduler during tests.
    with TestClient(app, raise_server_exceptions=True) as test_client:
        yield test_client
