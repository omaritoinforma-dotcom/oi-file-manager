FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json tools.json server.js ./
RUN addgroup -S oi && adduser -S oi -G oi
USER oi
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node","server.js"]
