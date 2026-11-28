import unittest
from unittest.mock import Mock

from upgrade_restaurant_city_index import add_cities, snapshot


class RestaurantCityIndexTest(unittest.TestCase):
    def test_reads_beyond_max_result_window_using_search_after(self):
        client = Mock()

        def request(method, path, query):
            self.assertEqual((method, path), ("POST", "/test/_search"))
            self.assertNotIn("from", query)
            start = query.get("search_after", [0])[0] + 1
            hits = [{"_id": str(identity), "_version": 2, "sort": [identity],
                     "_source": {"id": identity, "projectionVersion": 2}}
                    for identity in range(start, min(start + query["size"], 10004))]
            return {"_shards": {"failed": 0},
                    "hits": {"total": {"value": 10003, "relation": "eq"}, "hits": hits}}

        client.request.side_effect = request
        result = snapshot(client, "test")
        self.assertEqual(len(result), 10003)
        self.assertEqual(result["10003"]["version"], 2)
        self.assertEqual(client.request.call_count, 11)

    def test_copies_mysql_city_without_changing_versions_or_tombstones(self):
        documents = {"1": {"source": {"id": 1, "deleted": False}, "version": 3},
                     "2": {"source": {"id": 2, "deleted": True}, "version": 5}}
        rows = {"1": {"city_name": "成都市", "deleted_at": None, "search_projection_version": 3},
                "2": {"city_name": "重庆市", "deleted_at": "2026-11-28", "search_projection_version": 5}}
        result = add_cities(documents, rows)
        self.assertEqual(result["1"]["source"]["cityName"], "成都市")
        self.assertEqual(result["1"]["version"], 3)
        self.assertEqual(result["2"], documents["2"])
        self.assertNotIn("cityName", documents["1"]["source"])
        rows["1"]["search_projection_version"] = 4
        with self.assertRaisesRegex(RuntimeError, "versions differ"):
            add_cities(documents, rows)
