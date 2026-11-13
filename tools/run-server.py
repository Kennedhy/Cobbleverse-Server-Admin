"""Run a dedicated server and relaunch it after a clean restart requested by the panel."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs=argparse.REMAINDER, help="Java command and arguments")
    command = parser.parse_args().command
    if not command:
        parser.error("informe o comando Java do servidor")

    with tempfile.TemporaryDirectory(prefix="cobbleverse-restart-") as directory:
        signal = Path(directory) / "restart"
        environment = {**os.environ, "COBBLEVERSE_RESTART_SIGNAL": str(signal)}
        while True:
            result = subprocess.run(command, env=environment, check=False)
            if result.returncode != 0 or not signal.is_file():
                return result.returncode
            signal.unlink()
            print("Reiniciando o servidor Cobbleverse...", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
