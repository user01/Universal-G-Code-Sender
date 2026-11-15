from __future__ import annotations

from collections.abc import Iterator

import pytest

from ugs_sim.machine import ShapeokoGrbl
from ugs_sim.profile import load_profile
from ugs_sim.server import ThreadedSimServer
from ugs_sim.testing import GrblClient


@pytest.fixture
def machine() -> ShapeokoGrbl:
    return ShapeokoGrbl(load_profile(), time_scale=200.0)


@pytest.fixture
def sim(machine: ShapeokoGrbl) -> Iterator[ThreadedSimServer]:
    with ThreadedSimServer(machine) as server:
        yield server


@pytest.fixture
def client(sim: ThreadedSimServer) -> Iterator[GrblClient]:
    c = GrblClient(sim.port)
    c.banner()
    yield c
    c.close()


@pytest.fixture
def homed(client: GrblClient) -> GrblClient:
    assert client.command("$H", timeout=10)[-1] == "ok"
    return client
