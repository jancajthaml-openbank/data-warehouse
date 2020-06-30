if [ -f database.json ] ; then
  rm database.json
fi

echo "[info] extracting primary data to partitioned secondary data (first time)"

time python3 full_primary_data_extraction.py

echo "[info] extracting primary data to partitioned secondary data (second time)"

time python3 full_primary_data_extraction.py

echo "[info] running example queries over secondary data"

python3 example_queries.py
