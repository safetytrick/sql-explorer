import {Injectable, inject} from '@angular/core';
import {DefaultUrlSerializer, UrlTree} from '@angular/router';
import {ENV_PARAM, splitEnvPath, withEnvPath} from './environment-url';
import {EnvironmentService} from './environment.service';

/**
 * Puts the environment at the front of every app URL: the router works with
 * `/queries`, the address bar and every generated link say `/prod/queries`
 * (sqlx-e13.7).
 *
 * <p>WHY A SERIALIZER RATHER THAN A `:env` ROUTE. The environment is one
 * scalar with one owner, `EnvironmentService`, and a switch is a full
 * document load. Nesting every route under `:env` would make each of the
 * app's links, redirects and `navigate` calls carry it, and one that forgot
 * would drop it. Here the routes, links and redirects stay as they were:
 * `parse` takes the environment off the front, `serialize` puts the current
 * one back, so a `routerLink`, a `window.open` of a serialized tree and the
 * address bar all name it without anyone remembering to.
 *
 * <p>The legacy `?env=` is read at startup (`EnvironmentService`) and dropped
 * here, so an old link is rewritten to the path form as it opens.
 */
@Injectable()
export class EnvPathUrlSerializer extends DefaultUrlSerializer {

  private readonly environments = inject(EnvironmentService);

  override parse(url: string): UrlTree {
    const at = url.search(/[?#]/);
    const path = at < 0 ? url : url.slice(0, at);
    const tail = at < 0 ? '' : url.slice(at);
    const tree = super.parse(splitEnvPath(path).rest + tail);
    delete tree.queryParams[ENV_PARAM];
    return tree;
  }

  override serialize(tree: UrlTree): string {
    const {[ENV_PARAM]: _legacy, ...queryParams} = tree.queryParams;
    const plain = new UrlTree(tree.root, queryParams, tree.fragment);
    const url = super.serialize(plain);
    const env = this.environments.urlName();
    return env ? withEnvPath(url, env) : url;
  }
}
