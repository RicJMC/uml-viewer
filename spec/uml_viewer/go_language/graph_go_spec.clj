(ns uml-viewer.go-language.graph-go-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.go-language.graph-go :as go]
            [uml-viewer.graph :as graph]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-module []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "uml-go-" (System/nanoTime)))
    (.mkdirs)))

(describe "go module path"
  (it "reads the module line of go.mod"
    (should= "example.com/shop" (go/module-path "// c\nmodule example.com/shop\n\ngo 1.23\n"))
    (should-be-nil (go/module-path "go 1.23\n"))))

(describe "go file surface"
  (it "reads grouped and single imports, aliases, and blank imports"
    (should= ["context" "example.com/shop/internal/order" "github.com/jackc/pgx/v5" "embed"]
             (:imports (go/read-file
                         (str "package app\n\n"
                              "import (\n"
                              "\t\"context\"\n"
                              "\t// \"commented/out\"\n"
                              "\torder \"example.com/shop/internal/order\"\n"
                              "\t\"github.com/jackc/pgx/v5\"\n"
                              ")\n"
                              "import _ \"embed\"\n")))))

  (it "ignores import-like text inside strings and comments"
    (should= ["fmt"]
             (:imports (go/read-file
                         (str "package a\nimport \"fmt\"\n"
                              "/* import \"os\" */\n"
                              "var s = `import \"net\"`\n")))))

  (it "lists exported functions, methods on exported types, and exported types"
    ;; A method is `Type.Method` so go-metrics rows join the same op.
    (let [surface (go/read-file
                    (str "package order\n"
                         "type Service struct{}\n"
                         "type hidden struct{}\n"
                         "type (\n\tRepo interface{ Get() }\n\tID int\n)\n"
                         "func New() *Service { return nil }\n"
                         "func helper() {}\n"
                         "func (s *Service) Place(id ID) error { return nil }\n"
                         "func (s *Service) check() {}\n"
                         "func (h hidden) Visible() {}\n"
                         "func Map[T any](xs []T) []T { return xs }\n"
                         "func (l *List[T]) Push(x T) {}\n"))]
      (should= "order" (:package surface))
      (should= ["New" "Service.Place" "Map" "List.Push"] (:ops surface))
      (should= #{"Service" "Repo" "ID"} (set (map :name (:types surface))))))

  (it "names the receiver type of a method"
    (should= "Service" (go/receiver-type "(s *Service)"))
    (should= "List" (go/receiver-type "(l *List[T])"))
    (should= "Plain" (go/receiver-type "(Plain)"))))

(describe "go imports"
  (it "tells the standard library from everything else"
    (should (go/stdlib? "net/http"))
    (should-not (go/stdlib? "github.com/jackc/pgx/v5")))

  (it "names an outside import by its repository"
    (should= :jackc.pgx (go/foreign-id "github.com/jackc/pgx/v5/pgxpool"))
    (should= :x.text (go/foreign-id "golang.org/x/text/language"))
    (should= :yaml.v3 (go/foreign-id "gopkg.in/yaml.v3"))))

(describe "go graph"
  (it "scans packages, links project imports, and keeps outside libraries as foreign"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/shop\n\ngo 1.23\n")
      (spit-file dir "internal/order/order.go"
                 (str "package order\n"
                      "import (\n\t\"context\"\n\t\"example.com/shop/internal/platform/db\"\n"
                      "\t\"github.com/jackc/pgx/v5/pgxpool\"\n)\n"
                      "type Service struct{ pool *pgxpool.Pool }\n"
                      "func (s *Service) Place(ctx context.Context) error { return db.Do(ctx) }\n"))
      (spit-file dir "internal/order/order_test.go"
                 "package order\nimport \"example.com/shop/internal/billing\"\n")
      (spit-file dir "internal/platform/db/db.go"
                 "package db\nimport \"context\"\nfunc Do(ctx context.Context) error { return nil }\n")
      (spit-file dir "internal/billing/port.go"
                 "package billing\ntype Charger interface{ Charge() error }\n")
      (spit-file dir "internal/testdata/x.go" "package testdata\n")
      (let [g (graph/scan (graph/lookup :go) (io/file dir "internal")
                          {:prefix "example.com.shop.internal"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:order :platform.db :billing :jackc.pgx} (set (keys by-id)))
        (should= "example.com.shop.internal.order" (:ns (by-id :order)))
        (should= :go (:lang (by-id :order)))
        (should= ["Service" "Service.Place"] (mapv :name (:ops (by-id :order))))
        (should= :interface (:stereotype (by-id :billing)))
        (should (:foreign (by-id :jackc.pgx)))
        (should= #{[:order :platform.db :dependency]
                   [:order :jackc.pgx :dependency]}
                 edges)))))

(describe "go interfaces and embedding"
  (it "links interface implementations and cross-package embedding"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/bank\n\ngo 1.23\n")
      (spit-file dir "deps/deps.go"
                 (str "package deps\n\n"
                      "type Reader interface {\n"
                      "\tGet(id int) (string, error)\n"
                      "\tList() []string\n"
                      "}\n\n"
                      "type Store interface {\n"
                      "\tReader\n"
                      "\tPut(id int, v string) error\n"
                      "}\n\n"
                      "type Clock interface {\n"
                      "\tNow() int64\n"
                      "}\n"))
      (spit-file dir "model/model.go"
                 (str "package model\n\n"
                      "type Base struct{ ID int }\n\n"
                      "type User[T any] struct {\n"
                      "\tBase\n"
                      "\tData T\n"
                      "}\n"))
      (spit-file dir "store/postgres/repo.go"
                 (str "package postgres\n\n"
                      "import (\n"
                      "\td \"example.com/bank/deps\"\n"
                      "\t\"example.com/bank/model\"\n"
                      ")\n\n"
                      "type (\n"
                      "\tRepo struct {\n"
                      "\t\t*model.Base\n"
                      "\t\tdb string `json:\"db}\"`\n"
                      "\t}\n"
                      "\tcache map[string]struct{}\n"
                      ")\n\n"
                      "func (r *Repo) Get(id int) (string, error) { return \"\", nil }\n"
                      "func (r *Repo) List() []string { return nil }\n"
                      "func (r Repo) Put(id int, v string) error { return nil }\n"
                      "func helper() {}\n\n"
                      "var _ d.Clock = nil\n"))
      (spit-file dir "memory/mem.go"
                 (str "package memory\n\n"
                      "import \"example.com/bank/model\"\n\n"
                      "type Mem struct {\n"
                      "\tInner struct {\n"
                      "\t\tmodel.Base\n"
                      "\t}\n"
                      "}\n\n"
                      "func (m *Mem) Now() int64 { return 0 }\n"
                      "func (m *Mem) Get(id int) (string, error) { return \"\", nil }\n"
                      "func (m *Mem) List() []string { return nil }\n"))
      (spit-file dir "audit/audit.go"
                 (str "package audit\n\n"
                      "import \"example.com/bank/deps\"\n\n"
                      "type Log interface {\n"
                      "\tdeps.Reader\n"
                      "\tAppend(s string)\n"
                      "}\n"))
      (spit-file dir "journal/journal.go"
                 (str "package journal\n\n"
                      "type J struct{}\n\n"
                      "func (j *J) Append(s string) {}\n"
                      "func (j *J) Get(id int) (string, error) { return \"\", nil }\n"
                      "func (j *J) List() []string { return nil }\n"))
      (let [g (graph/scan (graph/lookup :go) dir {:prefix "example.com.bank"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            typed (set (keep (fn [{:keys [from to kind]}]
                               (when (not= :dependency kind) [from to kind]))
                             (:edges g)))]
        (should= :interface (:stereotype (by-id :deps)))
        (should= :interface (:stereotype (by-id :audit)))
        (should-be-nil (:stereotype (by-id :model)))
        (should-be-nil (:stereotype (by-id :store.postgres)))
        (should= #{[:store.postgres :deps :implements]
                   [:store.postgres :model :inheritance]
                   [:memory :deps :implements]
                   [:journal :deps :implements]
                   [:journal :audit :implements]
                   [:audit :deps :inheritance]}
                 typed)
        (should= ["Repo" "Repo.Get" "Repo.List" "Repo.Put"]
                 (mapv :name (:ops (by-id :store.postgres))))))))
