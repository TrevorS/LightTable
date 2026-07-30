import CodeMirror = require('codemirror');

// Define search commands. Depends on dialog.js or another
// implementation of the openDialog method.

// Replace works a little oddly -- it will do the replace on the next
// Ctrl-G (or whatever is bound to findNext) press. You prevent a
// replace by making sure the match is no longer selected when hitting
// Ctrl-G.

(function() {
  function searchOverlay(query: string | RegExp) {
    if (typeof query == "string") {
      var ignoreCase = query == query.toLowerCase();
      return {token: function(stream: CMStream) {
        if (stream.match(query, true, ignoreCase)) return "searching";
        stream.next();
        if(!stream.skipTo(query.charAt(0))) {
          if(!ignoreCase || !stream.skipTo(query.charAt(0).toUpperCase())) {
            stream.skipToEnd();
          }
        }
      }};
    }
    return {token: function(stream: CMStream) {
      if (stream.match(query)) return "searching";
      while (!stream.eol()) {
        stream.next();
        if (stream.match(query, false)) break;
      }
    }};
  }

  class SearchState {
    posFrom: CMPosition | null = null;
    posTo: CMPosition | null = null;
    origPos: CMPosition | null = null;
    /** The compiled query: a RegExp when the user typed /.../ , else a string. */
    query: string | RegExp | null = null;
    /** What the user actually typed, so an unchanged query is a no-op. */
    rawQuery: string | null = null;
    overlay: CMOverlay | null = null;
    mark: CMTextMarker | null = null;
  }
  function getSearchState(cm: CMEditor): SearchState {
    // The editor carries this opaquely; the shape belongs to this file.
    return (cm._searchState as SearchState) || (cm._searchState = new SearchState());
  }
  function getSearchCursor(cm: CMEditor, query: string | RegExp, pos?: CMPosition | null) {
    // Heuristic: if the query string is all lowercase, do a case insensitive search.
    return cm.getSearchCursor(query, pos, typeof query == "string" && query == query.toLowerCase());
  }
  function isRegex(s: any) {
    if(s instanceof RegExp) return true;
    return s.match(/^\/(.+)\/([a-z]*)$/);
  }
  function parseQuery(query: string) {
    var isRE = isRegex(query);
    try {
      var rx = new RegExp(isRE[1], isRE[2].indexOf("i") == -1 ? "" : "i");
      if(isRE && !"".match(rx)) {
        return rx;
      } else {
        return query;
      }
    } catch (e) {
      return query;
    }
  }
  function doSearch(cm: CMEditor, query: string, rev?: boolean) {
    var state = getSearchState(cm);
    cm.operation(function() {
      if(!query) {
        clearSearch(cm, true);
        return;
      }
      if (state.rawQuery == query) return;
      clearSearch(cm, false);
      state.rawQuery = query;
      state.query = parseQuery(query);
      cm.removeOverlay(state.overlay);
      state.overlay = searchOverlay(state.query);
      cm.addOverlay(state.overlay!);
      state.posFrom = state.posTo = cm.getCursor();
      if(!state.origPos) {
        state.origPos = state.posFrom;
      }
      findNext(cm, rev, true);
    });
  }
  function findNext(cm: CMEditor, rev?: boolean, allowCurrent?: boolean) {cm.operation(function() {
    var state = getSearchState(cm);
    var startingPoint = state.posTo;

    if(!state.query) { return; }

    if(allowCurrent) {
      startingPoint = state.origPos ? state.origPos : state.posFrom;
    } else {
      state.origPos = null;
      if(rev) {
        startingPoint = state.posFrom;
      }
    }

    var cursor = getSearchCursor(cm, state.query, startingPoint);
    if (!cursor.find(rev)) {
      cursor = getSearchCursor(cm, state.query, rev ? {line: cm.lineCount() - 1, ch: 0} : {line: 0, ch: 0});
      if (!cursor.find(rev)) return;
    }
    //cm.setSelection(cursor.from(), cursor.to());
    cm.setCursor(cursor.from());

    if(state.mark) { state.mark.clear(); }
    state.mark = cm.markText(cursor.from(), cursor.to(), {className: "searching-current"});
    state.posFrom = cursor.from(); state.posTo = cursor.to();
  });}
  function clearSearch(cm: CMEditor, hard: any) {cm.operation(function() {
    var state = getSearchState(cm);
    if (!state.query) return;
    if (hard) { state.origPos = null; }
    state.query = null;
    state.rawQuery = null;
    if(state.mark) { state.mark.clear(); }
    cm.removeOverlay(state.overlay);
  });}


  function replace(cm: CMEditor, text: string, rev?: boolean, all?: boolean) {
    // Nothing to replace until a search has run, which is also what makes
    // state.query non-null below.
    if (!getSearchState(cm).query) return;
    if (all) {
      cm.operation(function() {
        var state = getSearchState(cm);
        for (var cursor = getSearchCursor(cm, state.query!); cursor.find(rev);) {
          if (typeof state.query != "string") {
            // Was `match(query)`, which is not in scope in this function —
            // a ReferenceError on any regex replace-all. The other branch
            // below uses state.query, which is what this meant.
            var match = cm.getRange(cursor.from(), cursor.to()).match(state.query!);
            cursor.replace(text.replace(/\$(\d)/, function(_: string, i: string) {return (match && match[Number(i)]) || "";}));
          } else cursor.replace(text);
        }
      });
    } else {
      var state = getSearchState(cm);
      var cursor = getSearchCursor(cm, state.query!, state.posFrom);
      if(cursor.findNext(rev)) {
        cm.setSelection(cursor.from(), cursor.to());
        var match: RegExpMatchArray | null = null;
        if(isRegex(state.query!)) {
          match = cm.getRange(cursor.from(), cursor.to()).match(state.query!);
        }
        cursor.replace(typeof state.query == "string" ? text :
                       text.replace(/\$(\d)/, function(_: string, i: string) {return (match && match[Number(i)]) || "";}));
      }
    }
  }

  CodeMirror.commands.find = function(cm: CMEditor, query: string, rev?: boolean) { doSearch(cm, query, rev); };
  CodeMirror.commands.findNext = findNext;
  CodeMirror.commands.findPrev = function(cm: CMEditor, rev?: boolean) { findNext(cm, !rev); };
  CodeMirror.commands.clearSearch = function(cm: CMEditor) { clearSearch(cm, true); };
  CodeMirror.commands.getSearchState = getSearchState;
  CodeMirror.commands.replace = replace;
  CodeMirror.commands.replaceAll = function(cm: any, query: any, replace: any) { replace(cm, query, replace, true); };
})();
