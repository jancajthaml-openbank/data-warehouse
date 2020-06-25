echo "[info] extracting primary data to partitioned secondary data"

python3 full_primary_data_extraction.py

echo "[info] running example queries over secondary data"

python3 example_queries.py
