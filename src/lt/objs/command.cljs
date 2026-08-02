(ns lt.objs.command
  "Provide command manager and command related fns"
  (:require [lt.object :as object]))

(declare manager)

(def ^:private required-keys #{:command :desc :exec})

(declare by-id)

(defn command
  "Define a command given a map with the following keys:

  * :command (required) - Unique keyword name for command
  * :desc (required) - Brief description of command
  * :exec (required)  - Function to invoke when command is called
  * :hidden - When true, command is hidden from command bar. Not set by default"
  [cmd]
  (assert (every? cmd required-keys)
          (str "Command doesn't have required keys: " required-keys))
  ;; "Unique" is what the docstring above has always claimed and nothing
  ;; checked. A second namespace registering a key that is taken replaced the
  ;; first silently, and what you got was a command that ran something else —
  ;; which is worse than one that does not exist, because the command bar still
  ;; lists it and it still does a thing.
  ;;
  ;; Compared by `:desc` rather than by identity so that live editing stays
  ;; quiet: re-evaluating a namespace in this editor re-runs every `command`
  ;; form in it, and that is the feature rather than a mistake. Two genuinely
  ;; different commands sharing both a key and a description is not a case
  ;; worth keeping this silent for.
  (when-let [taken (by-id (:command cmd))]
    (when (not= (:desc taken) (:desc cmd))
      (object/safe-report-error
       (str "Two commands are registered as " (:command cmd) ": \""
            (:desc taken) "\" and \"" (:desc cmd)
            "\". The second wins, so the first is now unreachable."))))
  (object/update! manager [:commands] assoc (:command cmd) cmd)
  (when (:options cmd)
    (object/add-tags (:options cmd) [:command.options]))
  (object/raise manager :added cmd))

(defn by-id
  "Return the command registered under key `k`."
  [k]
  (-> @manager :commands (get (if (map? k)
                                (:command k)
                                k))))

(defn completions
  "Return command completions for `token`, for use in the command bar."
  [token]
  (if (and token
           (= (subs token 0 1) ":"))
    (map #(do #js {:completion (str (:command %)) :text (str (:command %))}) (vals (:commands @manager)))
    (map #(if-not (:desc %)
            #js {:completion (str (:command %)) :text (str (:command %))}
            #js {:completion (str (:command %)) :text (:desc %)})
         (vals (:commands @manager)))))

(defn exec!
  "Execute a Light Table command with the given args"
  [cmd & args]
  (let [cmd (by-id cmd)]
    (when (and cmd
               (:exec cmd))
      (if (:options cmd)
        (apply object/raise (first (object/by-tag :sidebar.command)) :exec! cmd args)
        (apply (:exec cmd) args)))))

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::command.manager
                :tags #{:command.manager}
                :commands {})

(def manager (object/create ::command.manager))
