import {Component, DestroyRef, DOCUMENT, inject} from '@angular/core';
import {HttpClient, HttpHeaders} from '@angular/common/http';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {combineLatest, map, of, catchError, startWith} from 'rxjs';
import {RouterLink} from '@angular/router';
import {EnvironmentService} from '../environment.service';
import {UserService} from '../user.service';
import {
  claudeCodeCommand, DESKTOP_AUTH_ENV, desktopJson, ENVIRONMENT_HEADER, isLocalOrigin, LOCAL_MCP_URL, McpConfig, mcpJson, MCP_PATH, mcpUrl,
  McpToolInfo, TOKEN_ENV, TOOLS_LIST_REQUEST, toolsOf
} from './mcp-setup';

interface Snippet {
  id: string;
  title: string;
  note: string;
  text: string;
}

/**
 * How to connect an MCP client to this instance (Admin → MCP). The endpoint
 * is the backend's own `/mcp`; the page fills each client's config in with
 * the URL it was served from and the environment being viewed, and lists the
 * tools by asking the endpoint, which doubles as a check that it answers.
 */
@Component({
  selector: 'app-mcp-setup',
  templateUrl: './mcp-setup.component.html',
  styleUrls: ['./mcp-setup.component.scss'],
  imports: [RouterLink]
})
export class McpSetupComponent {
  readonly environmentHeader = ENVIRONMENT_HEADER;
  readonly tokenEnv = TOKEN_ENV;
  readonly desktopAuthEnv = DESKTOP_AUTH_ENV;
  readonly localUrl = LOCAL_MCP_URL;

  config: McpConfig;
  snippets: Snippet[] = [];
  copied: string | null = null;
  /** Opened other than on the machine running SQL Explorer: through a tunnel, say. */
  remote = false;
  /** null while asking; the error text when the endpoint did not answer. */
  tools: McpToolInfo[] | null = null;
  private toolsFailure: {status?: number; message?: string} | null = null;

  private readonly environments = inject(EnvironmentService);

  constructor() {
    const origin = inject(DOCUMENT).location?.origin ?? 'http://localhost:4200';
    this.config = {url: mcpUrl(origin), environment: null};
    this.remote = !isLocalOrigin(origin);
    this.build();
    const destroyRef = inject(DestroyRef);

    // The selected environment, or failing that the backend's default named
    // explicitly — a config file outlives whatever the default is today.
    const fallback = this.environments.getEnvironments().pipe(
      map(list => list.defaultEnvironment ?? null),
      catchError(() => of(null)),
      startWith(null));
    combineLatest([this.environments.current$, fallback])
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(([current, byDefault]) => {
        this.config = {...this.config, environment: current ?? byDefault};
        this.build();
      });

    // with sign-in on, the endpoint wants an access token (sqlx-e13.5)
    inject(UserService).getCurrentUser()
      .pipe(map(user => user.mode === 'oidc'), catchError(() => of(false)), takeUntilDestroyed(destroyRef))
      .subscribe(token => {
        this.config = {...this.config, token};
        this.build();
      });

    // MCP's streamable HTTP asks for both; the stateless server answers JSON
    const headers = new HttpHeaders({Accept: 'application/json, text/event-stream'});
    inject(HttpClient).post<{result?: {tools?: []}}>(MCP_PATH, TOOLS_LIST_REQUEST, {headers})
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({
        next: response => { this.tools = toolsOf(response); },
        error: (error: {status?: number; message?: string}) => {
          this.tools = [];
          this.toolsFailure = error;
        }
      });
  }

  /** Why the endpoint did not list its tools; null when it did, or has not answered yet. */
  get toolsError(): string | null {
    const failure = this.toolsFailure;
    if (!failure) {
      return null;
    }
    // A browser always sends an Origin. With sign-in off the endpoint takes one
    // only from this machine; with it on, also the page's own address.
    if (failure.status === 403 && !this.config.token) {
      return 'with sign-in off it answers pages opened on localhost only';
    }
    return failure.status ? `HTTP ${failure.status}` : (failure.message ?? 'no answer');
  }

  get writeCount(): number {
    return this.tools?.filter(tool => !tool.readOnly).length ?? 0;
  }

  copy(snippet: Snippet) {
    navigator.clipboard?.writeText(snippet.text).then(() => {
      this.copied = snippet.id;
      setTimeout(() => { if (this.copied === snippet.id) { this.copied = null; } }, 1500);
    });
  }

  private build() {
    this.snippets = [
      {
        id: 'code-cli',
        title: 'Claude Code — command line',
        note: 'registers it for you; add --scope project to share it via .mcp.json',
        text: claudeCodeCommand(this.config)
      },
      {
        id: 'code-json',
        title: 'Claude Code — .mcp.json',
        note: 'at the root of a project, checked in for everyone working there',
        text: mcpJson(this.config)
      },
      {
        id: 'desktop',
        title: 'Claude Desktop',
        note: 'claude_desktop_config.json — Settings → Developer → Edit Config; needs Node for npx',
        text: desktopJson(this.config)
      }
    ];
  }
}
