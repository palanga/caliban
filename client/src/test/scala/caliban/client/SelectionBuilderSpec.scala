package caliban.client

import caliban.client.Selection.Directive
import caliban.client.TestData._
import caliban.client.Value.StringValue
import zio.test.Assertion._
import zio.test._

object SelectionBuilderSpec
    extends DefaultRunnableSpec(
      suite("SelectionBuilderSpec")(
        suite("query generation")(
          test("simple object") {
            val query =
              Queries.characters() {
                Character.name
              }
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(s, equalTo("characters{name}"))
          },
          test("combine 2 fields") {
            val query =
              Queries.characters() {
                Character.name ~ Character.nicknames
              }
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(s, equalTo("characters{name nicknames}"))
          },
          test("union type") {
            val query =
              Queries.characters() {
                Character.name ~
                  Character.nicknames ~
                  Character
                    .role(Role.Captain.shipName, Role.Pilot.shipName, Role.Mechanic.shipName, Role.Engineer.shipName)
              }
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(
              s,
              equalTo(
                "characters{name nicknames role{__typename ... on Captain{shipName} ... on Pilot{shipName} ... on Mechanic{shipName} ... on Engineer{shipName}}}"
              )
            )
          },
          test("argument") {
            val query =
              Queries.characters(Some(Origin.MARS)) {
                Character.name
              }
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(s, equalTo("""characters(origin:"MARS"){name}"""))
          },
          test("aliases") {
            val query =
              Queries
                .character("Amos Burton") {
                  Character.name
                }
                .copy(alias = Some("amos")) ~
                Queries
                  .character("Naomi Nagata") {
                    Character.name
                  }
                  .copy(alias = Some("naomi"))
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(
              s,
              equalTo("""amos:character(name:"Amos Burton"){name} naomi:character(name:"Naomi Nagata"){name}""")
            )
          },
          test("variables") {
            val query =
              Queries
                .character("Amos Burton") {
                  Character.name
                }
                .withAlias("amos") ~
                Queries
                  .character("Naomi Nagata") {
                    Character.name
                  }
                  .withAlias("naomi")
            val (s, variables) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = true)
            assert(s, equalTo("""amos:character(name:$name){name} naomi:character(name:$name1){name}""")) &&
            assert(variables.get("name"), isSome(equalTo((StringValue("Amos Burton"), "String!")))) &&
            assert(variables.get("name1"), isSome(equalTo((StringValue("Naomi Nagata"), "String!"))))
          },
          test("directives") {
            val query =
              Queries
                .character("Amos Burton") {
                  Character.name
                }
                .withDirective(Directive("yo", List(Argument("value", "what's up"))))
            val (s, _) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = false)
            assert(s, equalTo("""character(name:"Amos Burton") @yo(value:"what's up"){name}"""))
          },
          test("directives + variables") {
            val query =
              Queries
                .character("Amos Burton") {
                  Character.name
                }
                .withDirective(Directive("yo", List(Argument("value", "what's up"))))
            val (s, variables) = SelectionBuilder.toGraphQL(query.toSelectionSet, useVariables = true)
            assert(s, equalTo("""character(name:$name) @yo(value:$value){name}""")) &&
            assert(variables.get("name"), isSome(equalTo((StringValue("Amos Burton"), "String!")))) &&
            assert(variables.get("value"), isSome(equalTo((StringValue("what's up"), "String!"))))
          }
        )
      )
    )
