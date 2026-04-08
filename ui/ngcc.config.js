/**
 * Angular 9 NGCC configuration.
 *
 * @swimlane/ngx-charts@16 ships an ES6 class (InjectionService) in its ESM5
 * bundle, which trips the Angular 9 NGCC ES5 reflection host. Directing NGCC
 * to the ESM2015 bundle uses the ES2015 host instead, which handles native
 * ES6 classes correctly. This workaround is no longer needed after upgrading
 * to Angular 10+.
 */
module.exports = {
  packages: {
    '@swimlane/ngx-charts': {
      entryPoints: {
        '.': {
          override: {
            esm5: './fesm2015/swimlane-ngx-charts.js'
          }
        }
      }
    }
  }
};
