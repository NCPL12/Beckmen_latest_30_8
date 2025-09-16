import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { RouterModule } from '@angular/router';
import { LocationStrategy, HashLocationStrategy } from '@angular/common';

import { AppComponent } from './app.component';
import { routes } from './app.routes';  // adjust path if needed

@NgModule({
  declarations: [ AppComponent ],
  imports: [
    BrowserModule,
    RouterModule.forRoot(routes, { useHash: true })   // <-- enable hash routing
  ],
  providers: [
    { provide: LocationStrategy, useClass: HashLocationStrategy }  // <-- tell Angular to use hash strategy
  ],
  bootstrap: [ AppComponent ]
})
export class AppModule { }
