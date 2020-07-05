FROM library/postgres:13

ENV TERM=xterm

ENV POSTGRES_DB=openbank

ENV POSTGRES_HOST_AUTH_METHOD=trust

COPY init.sql /docker-entrypoint-initdb.d/

RUN chmod a+r /docker-entrypoint-initdb.d/*

CMD ["postgres", "-c", "client_min_messages=DEBUG1", "-c", "log_min_messages=DEBUG1"]
