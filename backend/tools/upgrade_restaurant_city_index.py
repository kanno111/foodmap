"""预检或升级本机商户索引 v004→v005，城市取自 MySQL，保留旧索引和 external 版本。"""
from __future__ import annotations

import argparse
import json
import os

from setup_restaurant_search_index import ALIAS, INDEX, LocalElasticsearch, load_spec, verify_definition
from upgrade_restaurant_reputation_index import write_batch


PREVIOUS = "restaurant-search-v004"
PAGE_SIZE = 1000


def alias_target(client):
    aliases = client.request("GET", "/_alias/" + ALIAS)
    if len(aliases) != 1:
        raise RuntimeError("Expected a single restaurant write index")
    index, state = next(iter(aliases.items()))
    if index not in (PREVIOUS, INDEX) or state.get("aliases", {}).get(ALIAS) != {"is_write_index": True}:
        raise RuntimeError("Unexpected restaurant alias target or options")
    return index


def snapshot(client, index):
    """按唯一ID继续读取，覆盖超过默认一万条窗口的全量文档。"""
    request = {"size": PAGE_SIZE, "track_total_hits": True, "version": True,
               "sort": [{"id": "asc"}], "query": {"match_all": {}}}
    result = {}
    total_count = None
    last_id = 0
    while True:
        page = client.request("POST", f"/{index}/_search", request)
        total = page["hits"]["total"]
        if page.get("timed_out") or page["_shards"]["failed"] or total["relation"] != "eq":
            raise RuntimeError("Incomplete index snapshot")
        if total_count is None:
            total_count = total["value"]
        hits = page["hits"]["hits"]
        if total["value"] != total_count or len(hits) != min(PAGE_SIZE, total_count - len(result)):
            raise RuntimeError("Index document count changed during snapshot")
        for hit in hits:
            document = hit["_source"]
            identity, version = document["id"], document["projectionVersion"]
            if identity <= last_id or hit["sort"] != [identity] or str(identity) != hit["_id"] or version < 1 or hit["_version"] != version:
                raise RuntimeError("Index identity or version mismatch")
            last_id = identity
            result[str(identity)] = {"source": document, "version": version}
        if len(result) == total_count:
            return result
        request["search_after"] = [last_id]


def add_cities(documents, rows):
    if documents.keys() != rows.keys():
        raise RuntimeError("MySQL and ES identities differ; synchronize v004 before upgrading")
    expected = {}
    for identity, item in documents.items():
        row = rows[identity]
        document = dict(item["source"])
        if item["version"] != row["search_projection_version"] or document["deleted"] != (row["deleted_at"] is not None):
            raise RuntimeError("MySQL and ES versions differ; synchronize v004 before upgrading")
        if not document["deleted"] and row["city_name"] is not None:
            document["cityName"] = row["city_name"]
        expected[identity] = {"source": document, "version": item["version"]}
    return expected


def run(client, connection, apply=False):
    spec = load_spec("005")
    if alias_target(client) == INDEX:
        verify_definition(client, spec)
        return {"status": "already-current", "index": INDEX}
    verify_definition(client, load_spec("004"), PREVIOUS)

    locked = False
    try:
        with connection.cursor() as cursor:
            if apply:
                cursor.execute("SET SESSION lock_wait_timeout=5")
                cursor.execute("LOCK TABLES restaurants READ, restaurant_search_outbox READ")
                locked = True
            cursor.execute("""SELECT EXISTS(SELECT 1 FROM restaurant_search_outbox
                JOIN restaurants ON restaurants.id=restaurant_search_outbox.restaurant_id
                  AND restaurants.search_projection_version=restaurant_search_outbox.projection_version
                WHERE restaurant_search_outbox.status IN ('PENDING','FAILED')) AS pending""")
            if cursor.fetchone()["pending"]:
                raise RuntimeError("Drain current-version Outbox tasks before upgrading")
            cursor.execute("SELECT id, search_projection_version, deleted_at, city_name FROM restaurants")
            rows = {str(row["id"]): row for row in cursor.fetchall()}
        if apply:
            client.request("POST", f"/{PREVIOUS}/_refresh")
        original = snapshot(client, PREVIOUS)
        expected = add_cities(original, rows)
        result = {"index": INDEX, "documents": len(expected),
                  "withCity": sum("cityName" in item["source"] for item in expected.values())}
        if not apply:
            return {"status": "preflight", **result}

        if client.optional(f"/{INDEX}") is None:
            candidate_spec = {**spec, "aliases": {}}
            if client.request("PUT", f"/{INDEX}", candidate_spec).get("acknowledged") is not True:
                raise RuntimeError("Candidate creation not acknowledged")
        verify_definition(client, spec, candidate=True)
        client.request("POST", f"/{INDEX}/_refresh")
        partial = snapshot(client, INDEX)
        if any(expected.get(identity) != item for identity, item in partial.items()):
            raise RuntimeError("Candidate contains unrelated or outdated documents")
        missing = [(identity, item) for identity, item in expected.items() if identity not in partial]
        for offset in range(0, len(missing), 100):
            write_batch(client, missing[offset:offset + 100], INDEX)
        client.request("POST", f"/{INDEX}/_refresh")
        if snapshot(client, INDEX) != expected or snapshot(client, PREVIOUS) != original or alias_target(client) != PREVIOUS:
            raise RuntimeError("Full verification failed; old alias retained")
        switched = client.request("POST", "/_aliases", {"actions": [
            {"remove": {"index": PREVIOUS, "alias": ALIAS, "must_exist": True}},
            {"add": {"index": INDEX, "alias": ALIAS, "is_write_index": True}},
        ]})
        if switched.get("acknowledged") is not True:
            raise RuntimeError("Alias switch uncertain; inspect before retry")
        verify_definition(client, spec)
        return {"status": "upgraded", **result}
    finally:
        if locked:
            with connection.cursor() as cursor:
                cursor.execute("UNLOCK TABLES")


def main():
    import pymysql

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--es-url", default="http://127.0.0.1:9200")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", required=True)
    parser.add_argument("--database", required=True)
    args = parser.parse_args()
    try:
        with pymysql.connect(host="127.0.0.1", port=args.mysql_port, user=args.mysql_user,
                             password=os.environ.get("FOODMAP_CITY_DB_PASSWORD", ""), database=args.database,
                             charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
                             connect_timeout=5, read_timeout=30, write_timeout=10, autocommit=True) as connection:
            result = run(LocalElasticsearch(args.es_url), connection, args.apply)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as error:
        # 不打印可能含连接账号、配置或文档内容的上游错误。
        print(json.dumps({"status": "inspect-before-retry", "error": type(error).__name__}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
