/**    / \____  _    ______   _____ / \____   ____  _____
 *    /  \__  \/ \  / \__  \ /  __//  \__  \ /    \/ __  \   Javaslang
 *  _/  // _\  \  \/  / _\  \\_  \/  // _\  \  /\  \__/  /   Copyright 2014 Daniel Dietrich
 * /___/ \_____/\____/\_____/____/\___\_____/_/  \_/____/    Licensed under the Apache License, Version 2.0
 */
package javaslang.parser;

import static java.util.stream.Collectors.joining;
import static javaslang.Requirements.require;
import static javaslang.Requirements.requireNonNull;
import static javaslang.Requirements.requireNotInstantiable;
import static javaslang.Requirements.requireNotNullOrEmpty;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javaslang.Strings;
import javaslang.Tuples;
import javaslang.Tuples.Tuple2;
import javaslang.either.Either;
import javaslang.either.Left;
import javaslang.either.Right;
import javaslang.match.Match;
import javaslang.match.Matchs;

//
// TODO:
// - Greedy & non-greedy quantors (?,+,*,??,+?,*?) // Naming: Quantor vs Multiplicity
//
// - Empty subrules
//   rule : ( "a" | | "c" ) # empty sub-rule alternative
//        |                 # empty alternative
//        ;
//
// - Charset [a-zA-Z]+ is a shorthand for Range ('a'..'z'|'A'..'Z')+ 
//   a-z = 'a'..'z' 
//   [a-zA-Z]+      Multiplicity(1..n)
//                         | 
//                      Choice 
//                       /   \ 
//                   Range   Range 
//                    / \     / \ 
//                   a   z   A   Z 
//
// - Whitespace handling
//   * Automatic whitespace handling within parser rules (use reserved word WHITESPACE instead of WS)
//   * Switch to non-automatic whitespace handling within lexer rules
//   * Leads to no distinction between lexer and parser phase.
//     Just one phase with context switch.
//     No switch for Literals in the context of parsers.
//     Context switch only within referenced lexer rules.
//
//   Lexer rules 
//   ----------- 
//   Charset : '[' (Char | Char '-' Char)+ ']'; // no auto-whitespace between lexer parts! 
//   Char : // Unicode character 
//
//   Parser rules 
//   ------------ 
//   example : Charset*; // whitespace between parser tokens allowed!
//
public final class Parsers {

	private static final Match<String> TO_STRING = Matchs
			.caze((Any any) -> ".")
			.caze((EOF eof) -> "EOF")
			.caze((Literal l) -> "'" + l.literal + "'")
			.caze((Quantifier q) -> "(" + stringify(q.parser.get()) + ")" + q.bounds.symbol)
			.caze((Rule r) -> Stream
					.of(r.alternatives)
					.map(p -> stringify(p.get()))
					.collect(joining("\n  | ", r.name + "\n  : ", "\n  ;")))
			.caze((Sequence s) -> Stream
					.of(s.parsers)
					.map(p -> stringify(p.get()))
					.collect(joining(" ")))
			.build();

	/**
	 * This class is not intended to be instantiated.
	 */
	private Parsers() {
		requireNotInstantiable();
	}

	public static final String stringify(Parser parser) {
		return TO_STRING.apply(parser);
	}

	/**
	 * Wildcard '.' parser. Matches a single, arbitrary character.
	 */
	static class Any implements Parser {

		static final Any INSTANCE = new Any();

		// hidden
		private Any() {
		}

		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			if (index < text.length()) {
				return new Right<>(new Tree<>("Any", Tuples.of(index, 1)));
			} else {
				return new Left<>(index);
			}
		}
	}

	/**
	 * End-of-file (EOF) parser. Recognized the end of the input.
	 */
	static class EOF implements Parser {

		static final EOF INSTANCE = new EOF();

		// hidden
		private EOF() {
		}

		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			if (index == text.length()) {
				return new Right<>(new Tree<>("EOF", Tuples.of(index, 0)));
			} else {
				return new Left<>(index);
			}
		}
	}

	/**
	 * String literal parser:
	 * 
	 * <pre>
	 * <code>
	 * 'funky string'
	 * </code>
	 * </pre>
	 */
	static class Literal implements Parser {

		final String literal;

		Literal(String literal) {
			this.literal = literal;
		}

		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			if (text.startsWith(literal, index)) {
				return new Right<>(new Tree<>("Literal", Tuples.of(index, literal.length())));
			} else {
				return new Left<>(index);
			}
		}
	}

	// TODO: javadoc
	static class Quantifier implements Parser {

		final Supplier<Parser> parser;
		final Bounds bounds;

		Quantifier(Supplier<Parser> parser, Bounds bounds) {
			requireNonNull(parser, "parser is null");
			requireNonNull(bounds, "bounds is null");
			this.parser = parser;
			this.bounds = bounds;
		}

		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			final Tree<Tuple2<Integer, Integer>> result = new Tree<>(bounds.name(), Tuples.of(
					index, 0));
			parseChildren(result, text, index);
			final boolean notMatched = result.getChildren().isEmpty();
			final boolean shouldHaveMatched = Bounds.ONE_TO_N.equals(bounds);
			if (notMatched && shouldHaveMatched) {
				return new Left<>(index);
			} else {
				return new Right<>(result);
			}
		}

		// TODO: rewrite this method (immutable & recursive)
		private void parseChildren(Tree<Tuple2<Integer, Integer>> tree, String text, int index) {
			final boolean unbound = !Bounds.ZERO_TO_ONE.equals(bounds);
			boolean found = true;
			do {
				final Either<Integer, Tree<Tuple2<Integer, Integer>>> child = parser.get().parse(
						text, tree.value._1 + tree.value._2);
				if (child.isRight()) {
					final Tree<Tuple2<Integer, Integer>> node = child.right().get();
					tree.attach(node);
					tree.value = Tuples.of(tree.value._1, node.value._2);
				} else {
					found = false;
				}
			} while (unbound && found);
		}

		static enum Bounds {

			ZERO_TO_ONE("?"), ZERO_TO_N("*"), ONE_TO_N("+"), // greedy
			ZERO_TO_ONE_NG("??"), ZERO_TO_N_NG("*?"), ONE_TO_N_NG("+?"); // non-greedy

			final String symbol;

			Bounds(String symbol) {
				this.symbol = symbol;
			}
		}
	}

	/**
	 * Grammar rule parser:
	 * 
	 * <pre>
	 * <code>
	 * ruleName
	 *   : subrule1
	 *   | subrule2
	 *   ;
	 * </code>
	 * </pre>
	 */
	static class Rule implements Parser {

		final String name;
		final Supplier<Parser>[] alternatives;

		@SafeVarargs
		Rule(String name, Supplier<Parser>... alternatives) {
			requireNonNull(name, "name is null");
			requireNotNullOrEmpty(alternatives, "No alternatives");
			this.name = name;
			this.alternatives = alternatives;
		}

		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			final Either<Integer, Tree<Tuple2<Integer, Integer>>> initial = new Left<>(index);
			return Stream
					.of(alternatives)
					.parallel()
					.map(parser -> parser.get().parse(text, index))
					.reduce(initial, (t1, t2) -> reduce(t1, t2, text, index),
							(t1, t2) -> reduce(t1, t2, text, index));
		}

		/**
		 * Make decision on following one of two parse trees. Each tree may may be a valid match on
		 * the input text or not.
		 * 
		 * @param tree1 First parse tree.
		 * @param tree2 Second parse tree.
		 * @param text The input text.
		 * @param index The current index.
		 * @return One of the given parse trees, which is either no match (Left) or a match (Right).
		 */
		// TODO: implement operator precedence & resolve ambiguities here
		private Either<Integer, Tree<Tuple2<Integer, Integer>>> reduce(
				Either<Integer, Tree<Tuple2<Integer, Integer>>> tree1,
				Either<Integer, Tree<Tuple2<Integer, Integer>>> tree2, String text, int index) {
			// if both trees are valid parse results, i.e. Right, then we found an ambiguity
			require(tree1.isLeft() || tree2.isLeft(),
					() -> "Ambiguity found at " + Strings.lineAndColumn(text, index) + ":\n" + text);
			if (tree1.isRight()) {
				return tree1; // first tree is a valid parse result
			} else if (tree2.isRight()) {
				return tree2; // second tree is a valid parse result
			} else if (tree1.left().get() >= tree2.left().get()) {
				return tree1; // both trees did not match, first tree consumed more characters
			} else {
				return tree2; // both trees did not match, second tree consumed more characters
			}
		}
	}

	// TODO: javadoc
	static class Sequence implements Parser {

		// TODO: make whitespace regex configurable
		private static final Pattern WHITESPACE = Pattern.compile("\\s*");

		final Supplier<Parser>[] parsers;

		@SafeVarargs
		public Sequence(Supplier<Parser>... parsers) {
			requireNotNullOrEmpty(parsers, "No parsers");
			this.parsers = parsers;
		}

		// TODO: rewrite this method (immutable & recursive)
		@Override
		public Either<Integer, Tree<Tuple2<Integer, Integer>>> parse(String text, int index) {
			// Starts with an emty root tree and successively attaches parsed children.
			final Either<Integer, Tree<Tuple2<Integer, Integer>>> initial = new Right<>(new Tree<>(
					"Sequence", Tuples.of(index, 0)));
			return Stream.of(parsers).reduce(initial, (tree, parser) -> {
				if (tree.isLeft()) {
					// first failure returned
					return tree;
				} else {
					// next parser parses at current index
					final Tree<Tuple2<Integer, Integer>> node = tree.right().get();
					final int lastIndex = node.value._1 + node.value._2;
					final Matcher matcher = WHITESPACE.matcher(text);
					final int currentIndex = matcher.find(lastIndex) ? matcher.end() : lastIndex;
					final Either<Integer, Tree<Tuple2<Integer, Integer>>> parsed = parser
							.get()
							.parse(text, currentIndex);
					// on success, attach token to tree
					if (parsed.isRight()) {
						final Tree<Tuple2<Integer, Integer>> child = parsed.right().get();
						node.attach(child);
						node.value = Tuples.of(node.value._1, child.value._2);
						return tree;
					} else {
						// parser failed => the whole sequence could not be parsed
						return parsed;
					}
				}
			}, (t1, t2) -> null); // combiner not used because stream is not parallel
		}
	}
}