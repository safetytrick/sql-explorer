import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {BehaviorSubject, of} from 'rxjs';
import {provideRouter} from '@angular/router';
import {EnvironmentService} from '../environment.service';
import {UserService} from '../user.service';
import {McpSetupComponent} from './mcp-setup.component';
import {claudeCodeCommand, desktopJson, isLocalOrigin, mcpJson, mcpUrl, toolsOf} from './mcp-setup';

describe('mcp-setup snippets', () => {
  const config = {url: 'http://localhost:4200/api/sql-explorer/mcp', environment: 'local'};

  it('puts the endpoint at the page origin', () => {
    expect(mcpUrl('http://localhost:4200')).toBe('http://localhost:4200/api/sql-explorer/mcp');
    expect(mcpUrl('https://db.example.com/')).toBe('https://db.example.com/api/sql-explorer/mcp');
  });

  it('tells this machine from a page opened through another address', () => {
    expect(isLocalOrigin('http://localhost:4200')).toBeTrue();
    expect(isLocalOrigin('http://127.0.0.1:4200')).toBeTrue();
    expect(isLocalOrigin('http://[::1]:4200')).toBeTrue();
    expect(isLocalOrigin('https://tunnel.example.test')).toBeFalse();
  });

  it('registers it with Claude Code by URL, the environment as a header', () => {
    expect(claudeCodeCommand(config)).toBe(
      'claude mcp add --transport http sql-explorer http://localhost:4200/api/sql-explorer/mcp \\\n'
      + '  --header \'X-Sql-Explorer-Environment: local\'');
    expect(claudeCodeCommand({...config, environment: null})).not.toContain('--header');
  });

  it('writes an http server into .mcp.json', () => {
    expect(JSON.parse(mcpJson(config)).mcpServers['sql-explorer']).toEqual({
      type: 'http', url: config.url, headers: {'X-Sql-Explorer-Environment': 'local'}
    });
    expect(JSON.parse(mcpJson({...config, environment: null})).mcpServers['sql-explorer'].headers).toBeUndefined();
  });

  it('bridges Claude Desktop to the URL through mcp-remote', () => {
    expect(JSON.parse(desktopJson(config)).mcpServers['sql-explorer']).toEqual({
      command: 'npx',
      args: ['-y', 'mcp-remote', config.url, '--header', 'X-Sql-Explorer-Environment:local']
    });
  });

  describe('with sign-in on, an access token (sqlx-e13.5)', () => {
    const withToken = {...config, token: true};

    it('sends it from the environment in the Claude Code command, so the shell fills it in', () => {
      expect(claudeCodeCommand(withToken)).toBe(
        'claude mcp add --transport http sql-explorer http://localhost:4200/api/sql-explorer/mcp \\\n'
        + '  --header \'X-Sql-Explorer-Environment: local\' \\\n'
        + '  --header "Authorization: Bearer $SQL_EXPLORER_TOKEN"');
    });

    it('references it in .mcp.json rather than holding it, so the file can be checked in', () => {
      expect(JSON.parse(mcpJson(withToken)).mcpServers['sql-explorer'].headers).toEqual({
        'X-Sql-Explorer-Environment': 'local',
        Authorization: 'Bearer ${SQL_EXPLORER_TOKEN}'
      });
      expect(JSON.parse(mcpJson({...withToken, environment: null})).mcpServers['sql-explorer'].headers)
        .toEqual({Authorization: 'Bearer ${SQL_EXPLORER_TOKEN}'});
    });

    it('passes it to mcp-remote as one variable holding the whole header value', () => {
      const server = JSON.parse(desktopJson(withToken)).mcpServers['sql-explorer'];
      expect(server.args).toEqual(['-y', 'mcp-remote', config.url, '--header', 'X-Sql-Explorer-Environment:local',
        '--header', 'Authorization:${SQL_EXPLORER_AUTH}']);
      expect(server.env.SQL_EXPLORER_AUTH).toMatch(/^Bearer /);
    });

    it('sends none with sign-in off', () => {
      expect(claudeCodeCommand(config)).not.toContain('Authorization');
      expect(mcpJson(config)).not.toContain('Authorization');
      expect(desktopJson(config)).not.toContain('Authorization');
    });
  });

  it('reads the tools, and which of them write, from tools/list', () => {
    expect(toolsOf({result: {tools: [
      {name: 'list_schemas', description: 'Schemas.', annotations: {readOnlyHint: true}},
      {name: 'add_tag', description: 'Tag it.', annotations: {readOnlyHint: false}}
    ]}})).toEqual([
      {name: 'list_schemas', description: 'Schemas.', readOnly: true},
      {name: 'add_tag', description: 'Tag it.', readOnly: false}
    ]);
    expect(toolsOf({})).toEqual([]);
  });
});

describe('McpSetupComponent', () => {
  function render(current: string | null, defaultEnvironment = 'staging', mode: 'oidc' | 'none' = 'none') {
    TestBed.configureTestingModule({
      imports: [McpSetupComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        {provide: UserService, useValue: {getCurrentUser: () => of({mode})}}, {
        provide: EnvironmentService,
        useValue: {
          current$: new BehaviorSubject<string | null>(current),
          getEnvironments: () => of({defaultEnvironment, environments: []})
        }
      }]
    });
    const fixture = TestBed.createComponent(McpSetupComponent);
    fixture.detectChanges();
    return fixture;
  }

  const listing = () => TestBed.inject(HttpTestingController).expectOne('/api/sql-explorer/mcp');

  it('fills the snippets with this origin and the selected environment', () => {
    const fixture = render('local');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-snippet=code-cli] pre')!.textContent)
      .toContain(`sql-explorer ${location.origin}/api/sql-explorer/mcp`);
    expect(el.querySelector('[data-snippet=code-json] pre')!.textContent)
      .toContain('"X-Sql-Explorer-Environment": "local"');
    expect(el.querySelectorAll('.snippet button').length).toBe(3);
  });

  it('with sign-in on, asks for an access token and links to making one', () => {
    const el = render('local', 'staging', 'oidc').nativeElement as HTMLElement;
    expect(el.querySelector('[data-snippet=code-json] pre')!.textContent)
      .toContain('"Authorization": "Bearer ${SQL_EXPLORER_TOKEN}"');
    const link = el.querySelector('.token-card a.make-token')!;
    expect(link.getAttribute('href')).toBe('/tokens?var-new=true');
  });

  it('with sign-in off, neither asks for a token nor sends one', () => {
    const el = render('local').nativeElement as HTMLElement;
    expect(el.querySelector('.token-card')).toBeNull();
    expect(el.querySelector('[data-snippet=code-json] pre')!.textContent).not.toContain('Authorization');
  });

  it('names the backend\'s default when nothing is selected', () => {
    const el = render(null).nativeElement as HTMLElement;
    expect(el.querySelector('[data-snippet=code-json] pre')!.textContent)
      .toContain('"X-Sql-Explorer-Environment": "staging"');
  });

  it('lists the tools the endpoint answers with, asking as an MCP client does', () => {
    const fixture = render('local');
    const request = listing();
    expect(request.request.method).toBe('POST');
    expect(request.request.body.method).toBe('tools/list');
    expect(request.request.headers.get('Accept')).toContain('text/event-stream');
    request.flush({result: {tools: [
      {name: 'list_schemas', description: 'Schemas.', annotations: {readOnlyHint: true}},
      {name: 'add_tag', description: 'Tag it.', annotations: {readOnlyHint: false}}
    ]}});
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.tool-name').length).toBe(2);
    expect(el.querySelectorAll('.writes').length).toBe(1);
    expect(el.querySelector('.status.ok')!.textContent).toContain('2 tools');
  });

  it('says why the listing failed when the endpoint refuses this page', () => {
    const fixture = render('local');
    listing().flush('', {status: 403, statusText: 'Forbidden'});
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.status.bad')!.textContent)
      .toContain('with sign-in off it answers pages opened on localhost only');
  });

  it('with sign-in on, does not blame the address for a 403', () => {
    const fixture = render('local', 'staging', 'oidc');
    listing().flush('', {status: 403, statusText: 'Forbidden'});
    fixture.detectChanges();
    const status = (fixture.nativeElement as HTMLElement).querySelector('.status.bad')!.textContent!;
    expect(status).toContain('HTTP 403');
    expect(status).not.toContain('localhost');
  });

  it('opened through another address, says when to use localhost instead', () => {
    const fixture = render('local');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.remote-note')).toBeNull();
    fixture.componentInstance.remote = true;
    fixture.detectChanges();
    expect(el.querySelector('.remote-note')!.textContent).toContain('http://localhost:4200/api/sql-explorer/mcp');
  });
});
