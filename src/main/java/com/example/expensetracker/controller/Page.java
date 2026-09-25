package com.example.expensetracker.controller;

import com.example.expensetracker.service.ExpenseService;

/** One of the pages the sidebar switches between. */
interface Page {

    /** Gives the page what it needs; called once, after it is loaded. */
    void setup(ExpenseService service, Runnable dataChanged);

    /** Reads the data again; called whenever the page is shown or the data changed. */
    void refresh();
}
