export default {
  '/api': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    logLevel: 'debug'
  },
  '/auth': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    logLevel: 'debug'
  },
  '/login': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    logLevel: 'debug'
  },
  '/logout': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    logLevel: 'debug'
  },
  '/oauth2': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    logLevel: 'debug'
  }
};
