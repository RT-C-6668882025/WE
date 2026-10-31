FROM node:22-alpine
WORKDIR /srv/we
COPY mcp/relay.mjs mcp/package.json ./mcp/
COPY app/src/main/assets/we-mcp-tools.json ./app/src/main/assets/we-mcp-tools.json
ENV PORT=8080 DATA_FILE=/data/relay.json
EXPOSE 8080
CMD ["node", "mcp/relay.mjs"]
