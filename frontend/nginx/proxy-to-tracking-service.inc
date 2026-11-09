# Shared by every location that forwards to the tracking service.
#
# Resolved per request through the variable (see `resolver` in default.conf), so nginx starts even
# when the backend is not up yet and follows the container to a new IP after a restart.
set $tracking_service http://tracking-service:8080;
proxy_pass $tracking_service;
proxy_http_version 1.1;

# $http_host, not $host: $host drops the port, and the tracking service must see "localhost:8081"
# to build redirect URIs that come back to this origin.
proxy_set_header Host $http_host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port $forwarded_port;
# nginx passes any request header it does not set itself straight through, so a client could send
# its own X-Forwarded-Host (or RFC 7239 Forwarded) and, because nginx is a trusted proxy, have the
# tracking service build the OIDC redirect_uri on a host of the client's choosing. Proto and Port
# are overwritten above; these two are removed (an empty value means "do not send"). The service
# ignores X-Forwarded-Host anyway (server.tomcat.remoteip.host-header); this is the second layer.
proxy_set_header X-Forwarded-Host "";
proxy_set_header Forwarded "";
proxy_set_header Connection "";
proxy_connect_timeout 3s;
proxy_read_timeout 30s;
