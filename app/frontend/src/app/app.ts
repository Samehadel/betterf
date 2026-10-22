import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { WorkspaceShell } from './workspace/workspace-shell';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, WorkspaceShell],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
