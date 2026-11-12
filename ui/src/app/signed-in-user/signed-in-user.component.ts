import { Component, OnInit } from '@angular/core';
import { CurrentUser, UserService } from '../user.service';

/**
 * Who is signed in, and Sign out, at the end of the nav (sqlx-e13.1).
 *
 * <p>With sign-in off it shows only a quiet "sign-in not configured": the
 * server is open to anyone who can reach it and records everything as the
 * legacy admin, which is worth knowing but not worth a banner across every
 * page.
 */
@Component({
  selector: 'app-signed-in-user',
  templateUrl: './signed-in-user.component.html',
  styleUrls: ['./signed-in-user.component.scss'],
})
export class SignedInUserComponent implements OnInit {

  current: CurrentUser | null = null;
  signingOut = false;
  signOutError: string | null = null;

  constructor(private users: UserService) { }

  ngOnInit() {
    // a 401 here is handled by the auth interceptor, which goes to sign in
    this.users.getCurrentUser().subscribe({
      next: current => this.current = current,
      error: () => this.current = null,
    });
  }

  /** Name, else email, else the provider's id for them. */
  displayName(current: CurrentUser): string {
    return current.user?.name || current.user?.email || current.subject || 'signed in';
  }

  /** Hover text: the email and the groups as the provider sent them. */
  detail(current: CurrentUser): string {
    const lines = [current.user?.email || '(no email)'];
    lines.push(current.groups.length ? 'groups: ' + current.groups.join(', ') : 'groups: none');
    return lines.join('\n');
  }

  signOut() {
    this.signingOut = true;
    this.signOutError = null;
    this.users.signOut().subscribe({
      error: err => {
        this.signingOut = false;
        this.signOutError = err?.message || 'sign-out failed';
      },
    });
  }
}
