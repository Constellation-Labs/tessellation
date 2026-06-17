apiVersion: 1
datasources:
  - name: prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://localhost:9090
    isDefault: true
    editable: true

  - name: ClickHouse
    uid: clickhouse
    type: grafana-clickhouse-datasource
    access: proxy
    isDefault: false
    editable: true
    jsonData:
      host: ${CLICKHOUSE_HOST}
      port: ${CLICKHOUSE_PORT}
      protocol: https
      secure: true
      username: ${CLICKHOUSE_USER}
      defaultDatabase: ${CLICKHOUSE_DATABASE}
    secureJsonData:
      password: ${CLICKHOUSE_PASSWORD}
