"""Create a separate server-only mods folder from a Modrinth pack installation."""

import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import uuid
import zipfile


JSON_DECODER = json.JSONDecoder(strict=False)


def skip_space_and_comments(text, index):
    while index < len(text):
        if text[index].isspace():
            index += 1
        elif text.startswith("//", index):
            newline = text.find("\n", index + 2)
            index = len(text) if newline < 0 else newline + 1
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end < 0:
                return len(text)
            index = end + 2
        else:
            break
    return index


def skip_json_value(text, index):
    index = skip_space_and_comments(text, index)
    if index >= len(text):
        return index
    if text[index] == '"':
        return JSON_DECODER.raw_decode(text, index)[1]
    if text[index] not in "[{":
        while (index < len(text) and text[index] not in ",]}"
               and not text.startswith(("//", "/*"), index)):
            index += 1
        return index

    closing = {"[": "]", "{": "}"}
    stack = [closing[text[index]]]
    index += 1
    while index < len(text) and stack:
        index = skip_space_and_comments(text, index)
        if index >= len(text):
            break
        character = text[index]
        if character == '"':
            index = JSON_DECODER.raw_decode(text, index)[1]
        elif character in "[{":
            stack.append(closing[character])
            index += 1
        elif character in "]}":
            if character != stack[-1]:
                return index
            stack.pop()
            index += 1
        else:
            index += 1
    return index


def top_level_environment(raw_metadata):
    """Read only the root environment field from strict or lenient Fabric JSON."""
    try:
        text = raw_metadata.decode("utf-8-sig")
    except UnicodeDecodeError:
        return None
    index = skip_space_and_comments(text, 0)
    if index >= len(text) or text[index] != "{":
        return None
    index += 1
    while index < len(text):
        index = skip_space_and_comments(text, index)
        if index >= len(text) or text[index] == "}":
            return None
        if text[index] != '"':
            return None
        key, index = JSON_DECODER.raw_decode(text, index)
        index = skip_space_and_comments(text, index)
        if index >= len(text) or text[index] != ":":
            return None
        value_index = skip_space_and_comments(text, index + 1)
        if key == "environment":
            if value_index < len(text) and text[value_index] == '"':
                value, _ = JSON_DECODER.raw_decode(text, value_index)
                return value
            return None
        index = skip_json_value(text, value_index)
        index = skip_space_and_comments(text, index)
        if index < len(text) and text[index] == ",":
            index += 1
        elif index < len(text) and text[index] == "}":
            return None
        else:
            return None


def is_client_only(jar_path):
    """Return True only when Fabric metadata explicitly marks the mod client-only."""
    try:
        with zipfile.ZipFile(jar_path) as jar:
            if "fabric.mod.json" not in jar.namelist():
                return False
            raw_metadata = jar.read("fabric.mod.json")
        try:
            metadata = json.loads(raw_metadata.decode("utf-8-sig"))
            environment = metadata.get("environment") if isinstance(metadata, dict) else None
        except (json.JSONDecodeError, UnicodeDecodeError):
            environment = top_level_environment(raw_metadata)
        return environment == "client"
    except (OSError, zipfile.BadZipFile) as failure:
        raise ValueError(f"JAR inválido: {jar_path.name}") from failure


def mod_relative_path(raw_path):
    if not isinstance(raw_path, str):
        return None
    if "\\" in raw_path:
        if raw_path.startswith("mods"):
            raise ValueError(f"Caminho inseguro no índice do pack: {raw_path}")
        return None
    path = PurePosixPath(raw_path)
    if not path.parts or path.parts[0] != "mods":
        return None
    if (path.is_absolute() or any(part in {"", ".", ".."} for part in raw_path.split("/"))
            or ":" in raw_path):
        raise ValueError(f"Caminho inseguro no índice do pack: {raw_path}")
    if path.suffix.lower() != ".jar" or len(path.parts) < 2:
        return None
    return Path(*path.parts[1:])


def prepare_server_mods(source_mods, index_file, output_mods):
    """Copy compatible JARs to a new directory, leaving the installed client pack intact."""
    source = Path(source_mods).resolve()
    index_path = Path(index_file).resolve()
    output = Path(output_mods).resolve()
    if not source.is_dir():
        raise ValueError(f"A pasta de mods de origem não existe: {source}")
    if not index_path.is_file():
        raise ValueError(f"O índice Modrinth não existe: {index_path}")
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("A origem e o destino precisam ser pastas separadas, sem sobreposição.")
    if output.exists():
        raise ValueError(f"O destino já existe. Escolha uma pasta nova para preservar os arquivos existentes: {output}")
    report_path = output.parent / f"{output.name}-report.txt"
    if report_path.exists():
        raise ValueError(f"O relatório já existe e não será sobrescrito: {report_path}")

    index = json.loads(index_path.read_text(encoding="utf-8"))
    if index.get("formatVersion") != 1 or not isinstance(index.get("files"), list):
        raise ValueError("O arquivo não é um índice Modrinth válido (formato 1).")

    indexed = {}
    for item in index["files"]:
        if not isinstance(item, dict):
            raise ValueError("O índice Modrinth contém uma entrada de arquivo inválida.")
        relative = mod_relative_path(item.get("path", ""))
        if relative is None:
            continue
        key = relative.as_posix()
        if key in indexed:
            raise ValueError(f"JAR duplicado no índice Modrinth: {key}")
        indexed[key] = item

    included = []
    skipped_client = []
    skipped_unsupported = []
    missing_optional = []
    plans = []
    seen = set()

    for key, item in indexed.items():
        relative = Path(*PurePosixPath(key).parts)
        source_jar = source / relative
        resolved_jar = source_jar.resolve()
        if resolved_jar != source and source not in resolved_jar.parents:
            raise ValueError(f"JAR do pack aponta para fora da pasta de origem: {source_jar}")
        server_environment = item.get("env", {}).get("server", "required")
        if server_environment == "unsupported":
            skipped_unsupported.append(relative.as_posix())
            continue
        if not source_jar.is_file():
            if server_environment == "optional":
                missing_optional.append(relative.as_posix())
                continue
            raise ValueError(f"JAR obrigatório do pack ausente: {source_jar}")
        if is_client_only(source_jar):
            skipped_client.append(relative.as_posix())
            continue
        plans.append((source_jar, relative))
        seen.add(relative.as_posix())

    extras = []
    for source_jar in sorted(source.rglob("*.jar")):
        if not source_jar.is_file():
            continue
        relative = source_jar.relative_to(source)
        key = relative.as_posix()
        if key in seen or key in indexed:
            continue
        if is_client_only(source_jar):
            skipped_client.append(f"extra: {key}")
            continue
        extras.append(key)
        plans.append((source_jar, relative))

    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_name(f"{output.name}.tmp-{uuid.uuid4().hex}")
    staging.mkdir()
    try:
        for source_jar, relative in plans:
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_jar, destination)
            included.append(relative.as_posix())
    except Exception:
        if staging.parent.resolve() == output.parent.resolve() and staging.name.startswith(output.name + ".tmp-"):
            shutil.rmtree(staging)
        raise
    staging.rename(output)

    report = [
        f"Pack: {index.get('name', 'unknown')} {index.get('versionId', '')}".strip(),
        f"JARs incluídos: {len(included)}",
        f"JARs client-only ignorados: {len(skipped_client)}",
        f"JARs server-unsupported ignorados: {len(skipped_unsupported)}",
        f"JARs opcionais ausentes na origem: {len(missing_optional)}",
        f"JARs locais extras incluídos: {len(extras)}",
        "",
        "Incluídos:",
        *included,
        "",
        "Ignorados por serem client-only:",
        *skipped_client,
        "",
        "Ignorados por incompatibilidade server declarada no índice:",
        *skipped_unsupported,
        "",
        "Opcionais ausentes na instalação de origem:",
        *missing_optional,
    ]
    report_path.write_text("\n".join(report) + "\n", encoding="utf-8")
    return {
        "pack": index.get("name", "unknown"),
        "version": index.get("versionId", ""),
        "included": included,
        "client_only": skipped_client,
        "unsupported": skipped_unsupported,
        "missing_optional": missing_optional,
        "extras": extras,
        "report": report_path,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Gera uma pasta de mods para servidor sem alterar a pasta de origem."
    )
    parser.add_argument("source_mods", type=Path, help="Pasta mods da instalação do modpack")
    parser.add_argument("modrinth_index", type=Path, help="Caminho para modrinth.index.json")
    parser.add_argument("output_mods", type=Path, help="Nova pasta mods para o servidor")
    args = parser.parse_args()
    try:
        result = prepare_server_mods(args.source_mods, args.modrinth_index, args.output_mods)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        parser.error(str(failure))
    print(f"Pack: {result['pack']} {result['version']}")
    print(f"JARs de servidor copiados: {len(result['included'])}")
    print(f"JARs client-only ignorados: {len(result['client_only'])}")
    print(f"JARs server-unsupported ignorados: {len(result['unsupported'])}")
    print(f"Relatório: {result['report']}")


if __name__ == "__main__":
    main()
