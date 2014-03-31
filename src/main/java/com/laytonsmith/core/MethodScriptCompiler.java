package com.laytonsmith.core;

import com.laytonsmith.annotations.breakable;
import com.laytonsmith.annotations.nolinking;
import com.laytonsmith.annotations.unbreakable;
import com.laytonsmith.core.Optimizable.OptimizationOption;
import com.laytonsmith.core.compiler.FileOptions;
import com.laytonsmith.core.constructs.CFunction;
import com.laytonsmith.core.constructs.CIdentifier;
import com.laytonsmith.core.constructs.CInt;
import com.laytonsmith.core.constructs.CLabel;
import com.laytonsmith.core.constructs.CNull;
import com.laytonsmith.core.constructs.CPreIdentifier;
import com.laytonsmith.core.constructs.CSlice;
import com.laytonsmith.core.constructs.CString;
import com.laytonsmith.core.constructs.CSymbol;
import com.laytonsmith.core.constructs.Construct;
import com.laytonsmith.core.constructs.IVariable;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.constructs.Token;
import com.laytonsmith.core.constructs.Token.TType;
import com.laytonsmith.core.constructs.Variable;
import com.laytonsmith.core.environments.Environment;
import com.laytonsmith.core.environments.GlobalEnv;
import com.laytonsmith.core.exceptions.ConfigCompileException;
import com.laytonsmith.core.exceptions.ConfigRuntimeException;
import com.laytonsmith.core.functions.Compiler;
import com.laytonsmith.core.functions.DataHandling;
import com.laytonsmith.core.functions.Function;
import com.laytonsmith.core.functions.FunctionBase;
import com.laytonsmith.core.functions.FunctionList;
import com.laytonsmith.core.functions.IncludeCache;
import com.laytonsmith.database.Profiles;
import com.laytonsmith.persistence.DataSourceException;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The MethodScriptCompiler class handles the various stages of compilation and
 * provides helper methods for execution of the compiled trees.
 */
public final class MethodScriptCompiler {
	
	private final static EnumSet<Optimizable.OptimizationOption> NO_OPTIMIZATIONS = EnumSet.noneOf(Optimizable.OptimizationOption.class);

	private final static FileOptions fileOptions = new FileOptions(new HashMap<String, String>());

	private MethodScriptCompiler() {
	}

	/**
	 * Lexes the script, and turns it into a token stream. This looks through the script
	 * character by character.
	 * @param script The script to lex
	 * @param file The file this script came from, or potentially null if the code is from
	 * a dynamic source
	 * @param inPureMScript If the script is in pure MethodScript, this should be true. Pure
	 * MethodScript is defined as code that doesn't have command alias wrappers.
	 * @return A stream of tokens
	 * @throws ConfigCompileException If compilation fails due to bad syntax
	 */
	@SuppressWarnings("UnnecessaryContinue")
	public static List<Token> lex(String script, File file, boolean inPureMScript) throws ConfigCompileException {
		script = script.replaceAll("\r\n", "\n");
		script = script + "\n";
		List<Token> token_list = new ArrayList<>();
		//Set our state variables
		boolean state_in_quote = false;
		int quoteLineNumberStart = 1;
		boolean in_smart_quote = false;
		int smartQuoteLineNumberStart = 1;
		boolean in_comment = false;
		int commentLineNumberStart = 1;
		boolean comment_is_block = false;
		boolean in_opt_var = false;
		boolean inCommand = (!inPureMScript);
		boolean inMultiline = false;
		StringBuilder buf = new StringBuilder();
		int line_num = 1;
		int column = 1;
		int lastColumn = 0;
		Target target = Target.UNKNOWN;
		//first we lex
		for (int i = 0; i < script.length(); i++) {
			Character c = script.charAt(i);
			Character c2 = null;
			Character c3 = null;
			if (i < script.length() - 1) {
				c2 = script.charAt(i + 1);
			}
			if (i < script.length() - 2) {
				c3 = script.charAt(i + 2);
			}

			column += i - lastColumn;
			lastColumn = i;
			if (c == '\n') {
				line_num++;
				column = 1;
				if(!inMultiline && !inPureMScript){
					inCommand = true;
				}
			}
			target = new Target(line_num, file, column);

			//Comment handling. If we're inside a string, bypass this though
			if (!state_in_quote && !in_smart_quote) {
				//Block comments start
				if (c == '/' && c2 == '*' && !in_comment) {
					in_comment = true;
					comment_is_block = true;
					commentLineNumberStart = line_num;
					i++;
					continue;
				}
				//Line comment start
				if (c == '#' && !in_comment) {
					in_comment = true;
					comment_is_block = false;
					continue;
				}
				//Double slash line comment start
				if(c == '/' && c2 == '/' && !in_comment){
					in_comment = true;
					comment_is_block = false;
					i++;
					continue;
				}
				//Block comment end
				if (c == '*' && c2 == '/' && in_comment && comment_is_block) {
					if (in_comment && comment_is_block) {
						in_comment = false;
						comment_is_block = false;
						i++;
						continue;
					} else if (!in_comment) {
						throw new ConfigCompileException("Unexpected block comment end", target);
					} //else they put it in a line comment, which is fine
				}
				//Line comment end
				if (c == '\n' && in_comment && !comment_is_block) {
					in_comment = false;
					continue;
				}
			}
			//Currently, if they are in a comment, we completely throw this away. Eventually block
			//comments that were started with /** will be kept and applied to the next identifier, but for the time
			//being, nothing.
			if (in_comment) {
				continue;
			}
			if(c == '+' && c2 == '=' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.PLUS_ASSIGNMENT, "+=", target));
				i++;
				continue;
			}
			if(c == '-' && c2 == '=' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MINUS_ASSIGNMENT, "-=", target));
				i++;
				continue;
			}
			if(c == '*' && c2 == '=' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MULTIPLICATION_ASSIGNMENT, "*=", target));
				i++;
				continue;
			}
			//This has to come before division and equals
			if(c == '/' && c2 == '=' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.DIVISION_ASSIGNMENT, "/=", target));
				i++;
				continue;
			}
			if(c == '.' && c2 == '=' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.CONCAT_ASSIGNMENT, "/=", target));
				i++;
				continue;				
			}
			//This has to come before subtraction and greater than
			if (c == '-' && c2 == '>' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.DEREFERENCE, "->", target));
				i++;
				continue;
			}
			//Increment and decrement must come before plus and minus
			if (c == '+' && c2 == '+' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.INCREMENT, "++", target));
				i++;
				continue;
			}
			if (c == '-' && c2 == '-' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.DECREMENT, "--", target));
				i++;
				continue;
			}

			if (c == '%' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MODULO, "%", target));
				continue;
			}

			//Math symbols must come after comment parsing, due to /* and */ block comments
			//Block comments are caught above
			if (c == '*' && c2 == '*' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.EXPONENTIAL, "**", target));
				i++;
				continue;
			}
			if (c == '*' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MULTIPLICATION, "*", target));
				continue;
			}
			if (c == '+' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.PLUS, "+", target));
				continue;
			}
			if (c == '-' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MINUS, "-", target));
				continue;
			}
			//Protect against commands
			if (c == '/' && !Character.isLetter(c2) && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.DIVISION, "/", target));
				continue;
			}
			//Logic symbols
			if (c == '>' && c2 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.GTE, ">=", target));
				i++;
				continue;
			}
			if (c == '<' && c2 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LTE, "<=", target));
				i++;
				continue;
			}
			//multiline has to come before gt/lt
			if (c == '<' && c2 == '<' && c3 == '<' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MULTILINE_END, "<<<", target));
				inMultiline = false;
				i++;
				i++;
				continue;
			}
			if (c == '>' && c2 == '>' && c3 == '>' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.MULTILINE_START, ">>>", target));
				inMultiline = true;
				i++;
				i++;
				continue;
			}
			if (c == '<' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LT, "<", target));
				continue;
			}
			if (c == '>' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.GT, ">", target));
				continue;
			}
			if (c == '=' && c2 == '=' && c3 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.STRICT_EQUALS, "===", target));
				i++;
				i++;
				continue;
			}
			if (c == '!' && c2 == '=' && c3 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.STRICT_NOT_EQUALS, "!==", target));
				i++;
				i++;
				continue;
			}
			if (c == '=' && c2 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.EQUALS, "==", target));
				i++;
				i++;
				continue;
			}
			if (c == '!' && c2 == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.NOT_EQUALS, "!=", target));
				i++;
				i++;
				continue;
			}
			if (c == '&' && c2 == '&' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LOGICAL_AND, "&&", target));
				i++;
				continue;
			}
			if (c == '|' && c2 == '|' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LOGICAL_OR, "||", target));
				i++;
				continue;
			}
			if (c == '!' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LOGICAL_NOT, "!", target));
				continue;
			}
			if (c == '{' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LCURLY_BRACKET, "{", target));
				continue;
			}
			if (c == '}' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.RCURLY_BRACKET, "}", target));
				continue;
			}
			//I don't want to use these symbols yet, especially since bitwise operations are rare.
//            if(c == '&' && !state_in_quote){
//                if (buf.length() > 0) {
//                    token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
//                    buf = new StringBuilder();
//                }
//                token_list.add(new Token(TType.BIT_AND, "&", target));  
//                continue;
//            }
//            if(c == '|' && !state_in_quote){
//                if (buf.length() > 0) {
//                    token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
//                    buf = new StringBuilder();
//                }
//                token_list.add(new Token(TType.BIT_OR, "|", target));  
//                continue;
//            }
//            if(c == '^' && !state_in_quote){
//                if (buf.length() > 0) {
//                    token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
//                    buf = new StringBuilder();
//                }
//                token_list.add(new Token(TType.BIT_XOR, "^", target));  
//                continue;
//            }

			if (c == '.' && c2 == '.' && !state_in_quote) {
				//This one has to come before plain .
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.SLICE, "..", target));
				i++;
				continue;
			}
			if (c == '.' && !Character.isDigit(c2) && !state_in_quote) {
				//if it's a number after this, it's a decimal
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.CONCAT, ".", target));
				continue;
			}
			if (c == ':' && c2 == ':' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.DEREFERENCE, "::", target));
				i++;
				continue;
			}
			if (c == '[' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LSQUARE_BRACKET, "[", target));
				in_opt_var = true;
				continue;
			}
			//This has to come after == and ===
			if (c == '=' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				if(inCommand){
					if (in_opt_var) {
						token_list.add(new Token(TType.OPT_VAR_ASSIGN, "=", target));
					} else {
						token_list.add(new Token(TType.ALIAS_END, "=", target));
						inCommand = false;
					}
				} else {
					token_list.add(new Token(TType.ASSIGNMENT, "=", target));
				}
				continue;
			}
			if (c == ']' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.RSQUARE_BRACKET, "]", target));
				in_opt_var = false;
				continue;
			}
			if (c == ':' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.LABEL, ":", target));
				continue;
			}
			if (c == ',' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.COMMA, ",", target));
				continue;
			}
			if (c == '(' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.FUNC_NAME, buf.toString(), target));
					buf = new StringBuilder();
				} else {
					//The previous token, if unknown, should be changed to a FUNC_NAME. If it's not
					//unknown, we may be doing standalone parenthesis, so auto tack on the __autoconcat__ function
					try {
						int count = 1;
						while (token_list.get(token_list.size() - count).type == TType.WHITESPACE) {
							count++;
						}
						if (token_list.get(token_list.size() - count).type == TType.UNKNOWN) {
							token_list.get(token_list.size() - count).type = TType.FUNC_NAME;
							//Go ahead and remove the whitespace here too, it breaks things
							count--;
							for (int a = 0; a < count; a++) {
								token_list.remove(token_list.size() - 1);
							}
						} else {
							token_list.add(new Token(TType.FUNC_NAME, "__autoconcat__", target));
						}
					} catch (IndexOutOfBoundsException e) {
						//This is the first element on the list, so, it's another autoconcat.
						token_list.add(new Token(TType.FUNC_NAME, "__autoconcat__", target));
					}
				}
				token_list.add(new Token(TType.FUNC_START, "(", target));
				continue;
			}
			if (c == ')' && !state_in_quote) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.FUNC_END, ")", target));
				continue;
			}
			if(c == ';' && !state_in_quote){
				if(buf.length() > 0){
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.SEMICOLON, ";", target));
				continue;
			}
			if (Character.isWhitespace(c) && !state_in_quote && c != '\n') {
				//keep the whitespace, but end the previous token, unless the last character
				//was also whitespace. All whitespace is added as a single space.                
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				if (token_list.size() > 0
						&& token_list.get(token_list.size() - 1).type != TType.WHITESPACE) {
					token_list.add(new Token(TType.WHITESPACE, " ", target));
				}
				continue;
			}
			if (c == '\'') {
				if (state_in_quote && !in_smart_quote) {
					token_list.add(new Token(TType.STRING, buf.toString(), target));
					buf = new StringBuilder();
					state_in_quote = false;
					continue;
				} else if (!state_in_quote) {
					state_in_quote = true;
					quoteLineNumberStart = line_num;
					in_smart_quote = false;
					if (buf.length() > 0) {
						token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
						buf = new StringBuilder();
					}
					continue;
				} else {
					//we're in a smart quote
					buf.append("'");
				}
			} else if (c == '"') {
				if (state_in_quote && in_smart_quote) {
					token_list.add(new Token(TType.SMART_STRING, buf.toString(), target));
					buf = new StringBuilder();
					state_in_quote = false;
					in_smart_quote = false;
					continue;
				} else if (!state_in_quote) {
					state_in_quote = true;
					in_smart_quote = true;
					smartQuoteLineNumberStart = line_num;
					if (buf.length() > 0) {
						token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
						buf = new StringBuilder();
					}
					continue;
				} else {
					//we're in normal quotes
					buf.append('"');
				}
			} else if (c == '\\') {
				//escaped characters
				if (state_in_quote) {
					if (c2 == '\\') {
						buf.append("\\");
					} else if (c2 == '\'') {
						buf.append("'");
					} else if (c2 == '"') {
						buf.append('"');
					} else if (c2 == 'n') {
						buf.append("\n");
					} else if (c2 == 'r'){
						buf.append("\r");
					} else if(c2 == 't'){
						buf.append("\t");
					} else if(c2 == '@' && in_smart_quote){
						buf.append("\\@");
					} else if (c2 == 'u') {
						//Grab the next 4 characters, and check to see if they are numbers
						StringBuilder unicode = new StringBuilder();
						for (int m = 0; m < 4; m++) {
							unicode.append(script.charAt(i + 2 + m));
						}
						try {
							Integer.parseInt(unicode.toString(), 16);
						} catch (NumberFormatException e) {
							throw new ConfigCompileException("Unrecognized unicode escape sequence", target);
						}
						buf.append(Character.toChars(Integer.parseInt(unicode.toString(), 16)));
						i += 4;
					} else {
						//Since we might expand this list later, don't let them
						//use unescaped backslashes
						throw new ConfigCompileException("The escape sequence \\" + c2 + " is not a recognized escape sequence", target);
					}

					i++;
					continue;
				} else {
					//Control character backslash
					token_list.add(new Token(TType.SEPERATOR, "\\", target));
				}
			} else if (state_in_quote) {
				buf.append(c);
				continue;
			} else if (c == '\n' && !comment_is_block) {
				if (buf.length() > 0) {
					token_list.add(new Token(TType.UNKNOWN, buf.toString(), target));
					buf = new StringBuilder();
				}
				token_list.add(new Token(TType.NEWLINE, "\n", target));
				in_comment = false;
				comment_is_block = false;
				continue;
			} else { //in a literal
				buf.append(c);
				continue;
			}
		} //end lexing
		if (state_in_quote) {
			if (in_smart_quote) {
				throw new ConfigCompileException("Unended string literal. You started the last double quote on line " + smartQuoteLineNumberStart, target);
			} else {
				throw new ConfigCompileException("Unended string literal. You started the last single quote on line " + quoteLineNumberStart, target);
			}
		}
		if (in_comment || comment_is_block) {
			throw new ConfigCompileException("Unended block comment. You started the comment on line " + commentLineNumberStart, target);
		}
		//look at the tokens, and get meaning from them. Also, look for improper symbol locations,
		//and go ahead and absorb unary +- into the token
		for (int i = 0; i < token_list.size(); i++) {
			Token t = token_list.get(i);
			Token prev2 = i - 2 >= 0 ? token_list.get(i - 2) : new Token(TType.UNKNOWN, "", t.target);
			Token prev1 = i - 1 >= 0 ? token_list.get(i - 1) : new Token(TType.UNKNOWN, "", t.target);
			Token next = i + 1 < token_list.size() ? token_list.get(i + 1) : new Token(TType.UNKNOWN, "", t.target);

			if (t.type == TType.UNKNOWN && prev1.type.isPlusMinus()
					&& !prev2.type.isIdentifier()) {
				//It is a negative/positive number. Absorb the sign
				t.value = prev1.value + t.value;
				token_list.remove(i - 1);
				i--;
			}

			if (t.type.equals(TType.UNKNOWN)) {
				if (t.val().matches("/.*")) {
					t.type = TType.COMMAND;
				} else if (t.val().matches("\\\\")) {
					t.type = TType.SEPERATOR;
				} else if (t.val().matches("\\$[a-zA-Z0-9_]+")) {
					t.type = TType.VARIABLE;
				} else if (t.val().matches("\\@[a-zA-Z0-9_]+")) {
					t.type = TType.IVARIABLE;
				} else if (t.val().equals("$")) {
					t.type = TType.FINAL_VAR;
				} else {
					t.type = TType.LIT;
				}
			}
			//Skip this check if we're not in pure mscript
			if(inPureMScript){
				if (t.type.isSymbol() && !t.type.isUnary() && !next.type.isUnary()) {
					if (prev1.type.equals(TType.FUNC_START) || prev1.type.equals(TType.COMMA)
							|| next.type.equals(TType.FUNC_END) || next.type.equals(TType.COMMA)
							|| prev1.type.isSymbol() || next.type.isSymbol()) {
						throw new ConfigCompileException("Unexpected symbol (" + t.val() + ")", t.getTarget());
					}
				}
			}

		}
		return token_list;
	}

	/**
	 * This function breaks the token stream into parts, separating the
	 * aliases/MethodScript from the command triggers
	 *
	 * @param tokenStream
	 * @return
	 * @throws ConfigCompileException
	 */
	public static List<Script> preprocess(List<Token> tokenStream) throws ConfigCompileException {
		if(tokenStream == null || tokenStream.isEmpty()){
			return new ArrayList<>();
		}
		//First, pull out the duplicate newlines
		ArrayList<Token> temp = new ArrayList<>();
		for (int i = 0; i < tokenStream.size(); i++) {
			try {
				if (tokenStream.get(i).type.equals(TType.NEWLINE)) {
					temp.add(new Token(TType.NEWLINE, "\n", tokenStream.get(i).target));
					while (tokenStream.get(++i).type.equals(TType.NEWLINE)) {
					}
				}
				if (tokenStream.get(i).type != TType.WHITESPACE) {
					temp.add(tokenStream.get(i));
				}
			} catch (IndexOutOfBoundsException e) {
			}
		}

		if (temp.size() > 0 && temp.get(0).type.equals(TType.NEWLINE)) {
			temp.remove(0);
		}

		tokenStream = temp;

		//Handle multiline constructs
		ArrayList<Token> tokens1_1 = new ArrayList<>();
		boolean inside_multiline = false;
		Token thisToken = null;
		for (int i = 0; i < tokenStream.size(); i++) {
			Token prevToken = i - 1 >= tokenStream.size() ? tokenStream.get(i - 1) : new Token(TType.UNKNOWN, "", Target.UNKNOWN);
			thisToken = tokenStream.get(i);
			Token nextToken = i + 1 < tokenStream.size() ? tokenStream.get(i + 1) : new Token(TType.UNKNOWN, "", Target.UNKNOWN);
			//take out newlines between the = >>> and <<< tokens (also the tokens)
			if (thisToken.type.equals(TType.ALIAS_END) && nextToken.val().equals(">>>")) {
				inside_multiline = true;
				tokens1_1.add(thisToken);
				i++;
				continue;
			}
			if (thisToken.val().equals("<<<")) {
				if (!inside_multiline) {
					throw new ConfigCompileException("Found multiline end symbol, and no multiline start found",
							thisToken.target);
				}
				inside_multiline = false;
				continue;
			}
			if (thisToken.val().equals(">>>") && inside_multiline) {
				throw new ConfigCompileException("Did not expect a multiline start symbol here, are you missing a multiline end symbol above this line?", thisToken.target);
			}
			if (thisToken.val().equals(">>>") && !prevToken.type.equals(TType.ALIAS_END)) {
				throw new ConfigCompileException("Multiline symbol must follow the alias_end token", thisToken.target);
			}

			//If we're not in a multiline construct, or we are in it and it's not a newline, add
			//it
			if (!inside_multiline || (inside_multiline && !thisToken.type.equals(TType.NEWLINE))) {
				tokens1_1.add(thisToken);
			}
		}
		
		assert thisToken != null;

		if (inside_multiline) {
			throw new ConfigCompileException("Expecting a multiline end symbol, but your last multiline alias appears to be missing one.", thisToken.target);
		}

		//take out newlines that are behind a \
		ArrayList<Token> tokens2 = new ArrayList<>();
		for (int i = 0; i < tokens1_1.size(); i++) {
			if (!tokens1_1.get(i).type.equals(TType.STRING) && tokens1_1.get(i).val().equals("\\") && tokens1_1.size() > i
					&& tokens1_1.get(i + 1).type.equals(TType.NEWLINE)) {
				tokens2.add(tokens1_1.get(i));
				i++;
				continue;
			}
			tokens2.add(tokens1_1.get(i));
		}

		//Now that we have all lines minified, we should be able to split
		//on newlines, and easily find the left and right sides

		List<Token> left = new ArrayList<>();
		List<Token> right = new ArrayList<>();
		List<Script> scripts = new ArrayList<>();
		boolean inLeft = true;
		for (Token t : tokens2) {
			if (inLeft) {
				if (t.type == TType.ALIAS_END) {
					inLeft = false;
				} else {
					left.add(t);
				}
			} else {
				if (t.type == TType.NEWLINE) {
					inLeft = true;
					// Check for spurious symbols, which indicate an issue with the
					// script, but ignore any whitespace.
					for(int j = left.size() - 1; j >= 0; j--){
						if(left.get(j).type == TType.NEWLINE){
							if(j > 0 && left.get(j - 1).type != TType.WHITESPACE){
								throw new ConfigCompileException("Unexpected token: " + left.get(j - 1).val(), left.get(j - 1).getTarget());
							}
						}
					}
					Script s = new Script(left, right);
					scripts.add(s);
					left = new ArrayList();
					right = new ArrayList();
				} else {
					right.add(t);
				}
			}
		}
		return scripts;
	}

	/**
	 * Compiles the token stream into a valid ParseTree. This also includes optimization
	 * and reduction.
	 * @param stream The token stream, as generated by {@link #lex()}
	 * @return A fully compiled, optimized, and reduced parse tree. If {@code stream} is
	 * null or empty, null is returned.
	 * @throws ConfigCompileException If the script contains syntax errors. Additionally,
	 * during optimization, certain methods may cause compile errors. Any function that
	 * can optimize static occurrences and throws a {@link ConfigRuntimeException} will
	 * have that exception converted to a ConfigCompileException.
	 */
	@SuppressWarnings("UnnecessaryContinue")
	public static ParseTree compile(List<Token> stream) throws ConfigCompileException {
		if(stream == null || stream.isEmpty()){
			return null;
		}
		Target unknown;
		try {
			//Instead of using Target.UNKNOWN, we can at least set the file.
			unknown = new Target(0, stream.get(0).target.file(), 0);
		} catch (Exception e) {
			unknown = Target.UNKNOWN;
		}

		List<Token> tempStream = new ArrayList<>(stream.size());
		List<Integer> irrelevantWhitespace = new ArrayList<>();
		int startingRelevant = -1;
		int endingRelevant = -1;
		int bracketBlocks = 0;
		for (int i = 0; i < stream.size(); i++) {
			if (!irrelevantWhitespace.isEmpty()) {
				//We have to be careful about the whitespace we remove, just
				//because we think it's irrelevant doesn't mean it is, for token
				//patterns that are < lookahead's size.
				for (int ii = irrelevantWhitespace.size() - 1; ii >= 0; ii--) {
					int index = irrelevantWhitespace.get(ii);
					if (index >= startingRelevant && index <= endingRelevant) {
						stream.remove(index);
					}
				}
				irrelevantWhitespace.clear();
				startingRelevant = -1;
				endingRelevant = -1;
			}
			//We need a 4 lookahead
			int lookahead = 4;
			Token[] t = new Token[lookahead + 1];
			int[] pos = new int[t.length - 1];
			//We could have removed all the indexes due to irrelevantNewlines being cleared, so if
			//i is >= stream.size(), we're done!
			if (i >= stream.size()) {
				break;
			}
			t[1] = stream.get(i);
			pos[0] = i;
			for (int ii = 2; ii < t.length; ii++) {
				int offset = i + (ii - 1) + irrelevantWhitespace.size();
				pos[ii - 1] = offset;
				t[ii] = offset < stream.size() ? stream.get(offset) : new Token(TType.UNKNOWN, "", t[1].target);
				if (t[ii].type == TType.NEWLINE || t[ii].type == TType.WHITESPACE) {
					//Skip newlines for the purposes of comparisons. If a newline was t[1], it'll still
					//get added in the right spot, but we do want to make note of the irrelevant newlines
					//afterwards, so we can toss them from the stream. We also have to deal with whitespace at this point.
					irrelevantWhitespace.add(offset);
					ii--;
				}
			}

			//Look for the longest; the "} else if(" and replace with elseif
			if (t[1].type == TType.RCURLY_BRACKET
					&& t[2].type == TType.LIT && t[2].value.equals("else")
					&& t[3].type == TType.FUNC_NAME && t[3].value.equals("if")
					&& t[4].type == TType.FUNC_START) {
				tempStream.add(new Token(TType.COMMA, ",", t[1].target));
				tempStream.add(new Token(TType.IDENTIFIER, "elseif", t[1].target));
				startingRelevant = pos[0];
				endingRelevant = pos[3];
				bracketBlocks--;
				i += 3;
				continue;
			}
			//Look for "} else {" and replace with else
			if (t[1].type == TType.RCURLY_BRACKET
					&& t[2].type == TType.LIT && t[2].value.equals("else")
					&& t[3].type == TType.LCURLY_BRACKET) {
				tempStream.add(new Token(TType.COMMA, ",", t[1].target));
				tempStream.add(new Token(TType.IDENTIFIER, "else", t[1].target));
				startingRelevant = pos[0];
				endingRelevant = pos[2];
				i += 2;
				continue;
			}
			//Look for "){" and replace with ,
			if (t[1].type == TType.FUNC_END
					&& t[2].type == TType.LCURLY_BRACKET) {
				tempStream.add(new Token(TType.SCOMMA, ",", t[1].target));
				startingRelevant = pos[0];
				endingRelevant = pos[1];
				i++;
				bracketBlocks++;
				continue;
			}
			//Look for "}" and replace with )
			if (t[1].type == TType.RCURLY_BRACKET) {
				tempStream.add(new Token(TType.FUNC_END, ")", t[1].target));
				bracketBlocks--;
				if (bracketBlocks < 0) {
					throw new ConfigCompileException("Unexpected right curly brace", unknown);
				}
				startingRelevant = endingRelevant = pos[0];
				continue;
			}

			//Nothing. Add it to the tempStream. Also, clear irrelevantNewlines,
			//because they may not be irrelevant at this point.            
			tempStream.add(t[1]);
			irrelevantWhitespace.clear();
		}
		if (bracketBlocks > 0) {
			throw new ConfigCompileException("Unclosed code block, check for missing right curly brace", unknown);
		}
		stream = tempStream;

		ParseTree tree = new ParseTree(fileOptions);
		tree.setData(new CNull(unknown));
		Stack<ParseTree> parents = new Stack<>();
		/**
		 * constructCount is used to determine if we need to use autoconcat
		 * when reaching a FUNC_END. The previous constructs, if the count
		 * is greater than 1, will be moved down into an autoconcat.
		 */
		Stack<AtomicInteger> constructCount = new Stack<>();
		Stack<AtomicBoolean> usesBraces = new Stack<>();
		constructCount.push(new AtomicInteger(0));
		parents.push(tree);

		tree.addChild(new ParseTree(new CFunction("__autoconcat__", unknown), fileOptions));
		parents.push(tree.getChildAt(0));
		tree = tree.getChildAt(0);
		constructCount.push(new AtomicInteger(0));

		/**
		 * The array stack is used to keep track of the number
		 * of square braces in use.
		 */
		Stack<AtomicInteger> arrayStack = new Stack<>();
		arrayStack.add(new AtomicInteger(-1));

		int parens = 0;
		Token t = null;

		for (int i = 0; i < stream.size(); i++) {
			t = stream.get(i);
			//Token prev2 = i - 2 >= 0 ? stream.get(i - 2) : new Token(TType.UNKNOWN, "", t.target);
			Token prev1 = i - 1 >= 0 ? stream.get(i - 1) : new Token(TType.UNKNOWN, "", t.target);
			Token next1 = i + 1 < stream.size() ? stream.get(i + 1) : new Token(TType.UNKNOWN, "", t.target);
			Token next2 = i + 2 < stream.size() ? stream.get(i + 2) : new Token(TType.UNKNOWN, "", t.target);
			//Token next3 = i + 3 < stream.size() ? stream.get(i + 3) : new Token(TType.UNKNOWN, "", t.target);
			Token nextNonWhitespace = new Token(TType.UNKNOWN, "", t.target);
			int nextNonWhitespaceIndex = -1;
			Token nextNonWhitespace2 = new Token(TType.UNKNOWN, "", t.target);
			int nextNonWhitespaceIndex2 = -1;
			Token nextNonWhitespace3 = new Token(TType.UNKNOWN, "", t.target);
			int nextNonWhitespaceIndex3 = -1;
			Token prevNonWhitespace = new Token(TType.UNKNOWN, "", t.target);
			int prevNonWhitespaceIndex = -1;
			for(int j = i + 1; j < stream.size(); j++){
				Token temp = stream.get(j);
				if(!temp.type.isWhitespace()){
					nextNonWhitespace = temp;
					nextNonWhitespaceIndex = j;
					break;
				}
			}
			for(int j = nextNonWhitespaceIndex + 1; j < stream.size(); j++){
				Token temp = stream.get(j);
				if(!temp.type.isWhitespace()){
					nextNonWhitespace2 = temp;
					nextNonWhitespaceIndex2 = j;
					break;
				}
			}
			for(int j = nextNonWhitespaceIndex2 + 1; j < stream.size(); j++){
				Token temp = stream.get(j);
				if(!temp.type.isWhitespace()){
					nextNonWhitespace3 = temp;
					nextNonWhitespaceIndex3 = j;
					break;
				}
			}
			for(int j = i - 1; j > 0; j--){
				Token temp = stream.get(j);
				if(!temp.type.isWhitespace()){
					prevNonWhitespace = temp;
					prevNonWhitespaceIndex = j;
					break;
				}
			}

			//Associative array/label handling
			if (nextNonWhitespace.type.equals(TType.LABEL)) {
				//If it's not an atomic identifier it's an error.
				if(!t.type.isAtomicLit() && t.type != TType.IVARIABLE){
					throw new ConfigCompileException("Invalid label specified", t.getTarget());
				}
				Construct val;
				if(t.type == TType.IVARIABLE){
					val = new IVariable(t.val(), t.target);
				} else {
					val = Static.resolveConstruct(t.val(), t.target);
				}
				tree.addChild(new ParseTree(new CLabel(val), fileOptions));
				constructCount.peek().incrementAndGet();
				i = nextNonWhitespaceIndex; //Move forward past any whitespace
				continue;
			}
			if(t.type == TType.LABEL && tree.getChildren().size() > 0){
				ParseTree cc = tree.getChildren().get(tree.getChildren().size() - 1);
				if(cc.getData() instanceof CSlice){
					//Special case where a slice is being used as a label.
					//Replace the value in the tree with a label, then continue.
					tree.removeChildAt(tree.getChildren().size() - 1);
					tree.addChild(new ParseTree(new CLabel(cc.getData()), fileOptions));
					constructCount.peek().incrementAndGet();
					continue;
				}
			}

			//Array notation handling
			if (t.type.equals(TType.LSQUARE_BRACKET)) {
				arrayStack.push(new AtomicInteger(tree.getChildren().size() - 1));
				continue;
			} else if (t.type.equals(TType.RSQUARE_BRACKET)) {
				boolean emptyArray = false;
				if (prevNonWhitespace.type.equals(TType.LSQUARE_BRACKET)) {
					emptyArray = true;
				}
				if (arrayStack.size() == 1) {
					throw new ConfigCompileException("Mismatched square bracket", t.target);
				}
				//array is the location of the array
				int array = arrayStack.pop().get();
				//index is the location of the first node with the index
				int index = array + 1;
				if (!tree.hasChildren()) {
					throw new ConfigCompileException("Brackets are illegal here", t.target);
				}
				ParseTree myArray = tree.getChildAt(array);
				ParseTree myIndex;
				if (!emptyArray) {
					myIndex = new ParseTree(new CFunction("__autoconcat__", myArray.getTarget()), fileOptions);
					
					for (int j = index; j < tree.numberOfChildren(); j++) {
						myIndex.addChild(tree.getChildAt(j));
					}
				} else {
					myIndex = new ParseTree(new CSlice("0..-1", t.target), fileOptions);
				}
				tree.setChildren(tree.getChildren().subList(0, array));
				ParseTree arrayGet = new ParseTree(new CFunction("array_get", t.target), fileOptions);
				arrayGet.addChild(myArray);
				arrayGet.addChild(myIndex);
				tree.addChild(arrayGet);
				constructCount.peek().set(constructCount.peek().get() - myIndex.numberOfChildren());
				continue;
			}

			//Smart strings
			if (t.type == TType.SMART_STRING) {
				ParseTree function = new ParseTree(fileOptions);
				function.setData(new CFunction(new Compiler.smart_string().getName(), t.target));
				ParseTree string = new ParseTree(fileOptions);
				string.setData(new CString(t.value, t.target));
				function.addChild(string);
				tree.addChild(function);
				constructCount.peek().incrementAndGet();
				continue;
			}

			if (t.type == TType.DEREFERENCE) {
				//Currently unimplemented, but going ahead and making it strict
				throw new ConfigCompileException("The '" + t.val() + "' symbol is not currently allowed in raw strings. You must quote all"
						+ " symbols.", t.target);
			}

			if (t.type.equals(TType.FUNC_NAME)) {
				CFunction func = new CFunction(t.val(), t.target);
				ParseTree f = new ParseTree(func, fileOptions);
				tree.addChild(f);
				constructCount.push(new AtomicInteger(0));
				tree = f;
				parents.push(f);
			} else if (t.type.equals(TType.FUNC_START)) {
				if (!prev1.type.equals(TType.FUNC_NAME)) {
					throw new ConfigCompileException("Unexpected parenthesis", t.target);
				}
				parens++;
				usesBraces.push(new AtomicBoolean(false));
			} else if (t.type.equals(TType.FUNC_END)) {
				if (parens <= 0) {
					throw new ConfigCompileException("Unexpected parenthesis", t.target);
				}
				parens--;
				ParseTree function = parents.pop();
				if (usesBraces.peek().get()) {
					Function f;
					try {
						f = (Function) FunctionList.getFunction(function.getData());
					} catch (ConfigCompileException e) {
						throw new ConfigCompileException("Could not find function " + function.getData().val(), t.target);
					}
					if (!f.allowBraces()) {
						throw new ConfigCompileException("Improper use of braces with " + f.getName() + "()", t.target);
					}
				}
				if (constructCount.peek().get() > 1) {
					//We need to autoconcat some stuff
					int stacks = constructCount.peek().get();
					int replaceAt = tree.getChildren().size() - stacks;
					ParseTree c = new ParseTree(new CFunction("__autoconcat__", tree.getTarget()), fileOptions);
					List<ParseTree> subChildren = new ArrayList<>();
					for (int b = replaceAt; b < tree.numberOfChildren(); b++) {
						subChildren.add(tree.getChildAt(b));
					}
					c.setChildren(subChildren);
					if (replaceAt > 0) {
						List<ParseTree> firstChildren = new ArrayList<>();
						for (int d = 0; d < replaceAt; d++) {
							firstChildren.add(tree.getChildAt(d));
						}
						tree.setChildren(firstChildren);
					} else {
						tree.removeChildren();
					}
					tree.addChild(c);
				}
				usesBraces.pop();
				constructCount.pop();
				try {
					constructCount.peek().incrementAndGet();
				} catch (EmptyStackException e) {
					throw new ConfigCompileException("Unexpected end parenthesis", t.target);
				}
				try {
					tree = parents.peek();
				} catch (EmptyStackException e) {
					throw new ConfigCompileException("Unexpected end parenthesis", t.target);
				}
			} else if (t.type.equals(TType.COMMA) || t.type == TType.SCOMMA) {
				if (t.type == TType.SCOMMA) {
					usesBraces.peek().set(true);
				}
				if (constructCount.peek().get() > 1) {
					int stacks = constructCount.peek().get();
					int replaceAt = tree.getChildren().size() - stacks;
					ParseTree c = new ParseTree(new CFunction("__autoconcat__", unknown), fileOptions);
					List<ParseTree> subChildren = new ArrayList<>();
					for (int b = replaceAt; b < tree.numberOfChildren(); b++) {
						subChildren.add(tree.getChildAt(b));
					}
					c.setChildren(subChildren);
					if (replaceAt > 0) {
						List<ParseTree> firstChildren = new ArrayList<>();
						for (int d = 0; d < replaceAt; d++) {
							firstChildren.add(tree.getChildAt(d));
						}
						tree.setChildren(firstChildren);
					} else {
						tree.removeChildren();
					}
					tree.addChild(c);
				}
				constructCount.peek().set(0);
				continue;
			}
			if(t.type == TType.SLICE){
				//We got here because the previous token isn't being ignored, because it's
				//actually a control character, instead of whitespace, but this is a
				//"empty first" slice notation. Compare this to the code below.
				try{
					String value = nextNonWhitespace.val();
					i = nextNonWhitespaceIndex - 1;
					if(nextNonWhitespace.type == TType.MINUS || nextNonWhitespace.type == TType.PLUS){
						value = nextNonWhitespace.val() + nextNonWhitespace3.val();
						i = nextNonWhitespaceIndex2 - 1;
					}
					CSlice slice = new CSlice(".." + value, nextNonWhitespace.getTarget());
					tree.addChild(new ParseTree(slice, fileOptions));
					i++;
					continue;
				} catch(ConfigRuntimeException ex){
					//CSlice can throw CREs, but at this stage, we have to
					//turn them into a CCE.
					throw new ConfigCompileException(ex);
				}
			}
			if (nextNonWhitespace.type.equals(TType.SLICE)) {
				//Slice notation handling
				try {
					CSlice slice;
					if (t.type.isSeparator() || (t.type.isWhitespace() && prevNonWhitespace.type.isSeparator())) {
						//empty first
						String value = nextNonWhitespace2.val();
						i = nextNonWhitespaceIndex2 - 1;
						if(nextNonWhitespace2.type == TType.MINUS || nextNonWhitespace2.type == TType.PLUS){
							value = nextNonWhitespace2.val() + nextNonWhitespace3.val();
							i = nextNonWhitespaceIndex3 - 1;
						}
						slice = new CSlice(".." + value, nextNonWhitespace.getTarget());
					} else if (nextNonWhitespace2.type.isSeparator()) {
						//empty last
						Token first = t;
						String modifier = "";
						if(prevNonWhitespace.type == TType.MINUS || prevNonWhitespace.type == TType.PLUS){
							//The negative would have already been inserted into the tree
							modifier = prevNonWhitespace.val();
							tree.removeChildAt(tree.getChildren().size() - 1);
						}
						slice = new CSlice(modifier + first.value + "..", first.target);
						i = nextNonWhitespaceIndex2 - 2;
					} else {
						//both are provided
						String modifier1 = "";
						if(prevNonWhitespace.type == TType.MINUS || prevNonWhitespace.type == TType.PLUS){
							//It's a negative, incorporate that here, and remove the
							//minus from the tree
							modifier1 = prevNonWhitespace.val();
							tree.removeChildAt(tree.getChildren().size() - 1);
						}
						Token first = t;
						if(first.type.isWhitespace()){
							first = prevNonWhitespace;
						}
						Token second = nextNonWhitespace2;
						i = nextNonWhitespaceIndex2 - 1;
						String modifier2 = "";
						if(nextNonWhitespace2.type == TType.MINUS || nextNonWhitespace2.type == TType.PLUS){
							modifier2 = nextNonWhitespace2.val();
							second = nextNonWhitespace3;
							i = nextNonWhitespaceIndex3 - 1;
						}

						slice = new CSlice(modifier1 + first.value + ".." + modifier2 + second.value, t.target);
					}
					i++;
					tree.addChild(new ParseTree(slice, fileOptions));
					continue;
				} catch(ConfigRuntimeException ex){
					//CSlice can throw CREs, but at this stage, we have to
					//turn them into a CCE.
					throw new ConfigCompileException(ex);
				}
			} else if (t.type == TType.LIT) {
				tree.addChild(new ParseTree(Static.resolveConstruct(t.val(), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.equals(TType.STRING) || t.type.equals(TType.COMMAND)) {
				tree.addChild(new ParseTree(new CString(t.val(), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.equals(TType.IDENTIFIER)) {
				tree.addChild(new ParseTree(new CPreIdentifier(t.val(), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.equals(TType.IVARIABLE)) {
				tree.addChild(new ParseTree(new IVariable(t.val(), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.equals(TType.UNKNOWN)) {
				tree.addChild(new ParseTree(Static.resolveConstruct(t.val(), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.isSymbol()) { //Logic and math symbols
				tree.addChild(new ParseTree(new CSymbol(t.val(), t.type, t.target), fileOptions));
				constructCount.peek().incrementAndGet();
			} else if (t.type.equals(TType.VARIABLE) || t.type.equals(TType.FINAL_VAR)) {
				tree.addChild(new ParseTree(new Variable(t.val(), null, false, t.type.equals(TType.FINAL_VAR), t.target), fileOptions));
				constructCount.peek().incrementAndGet();
				//right_vars.add(new Variable(t.val(), null, t.line_num));
			}
			
		}
		
		assert t != null;
		
		if (arrayStack.size() != 1) {
			throw new ConfigCompileException("Mismatched square brackets", t.target);
		}
		if (parens != 0) {
			throw new ConfigCompileException("Mismatched parenthesis", t.target);
		}

		Stack<List<Procedure>> procs = new Stack<>();
		procs.add(new ArrayList<Procedure>());
		optimize(tree, procs);
		link(tree);
		checkLabels(tree);
		checkBreaks(tree);
		parents.pop();
		tree = parents.pop();
		return tree;
	}
	
	/**
	 * Recurses down the tree and ensures that breaks don't bubble up past
	 * procedures or the root code tree.
	 * @param tree
	 * @throws ConfigCompileException 
	 */
	private static void checkBreaks(ParseTree tree) throws ConfigCompileException {
		checkBreaks0(tree, 0, null);
	}
	
	private static void checkBreaks0(ParseTree tree, long currentLoops, String lastUnbreakable) throws ConfigCompileException {
		if(!(tree.getData() instanceof CFunction)){
			//Don't care about these
			return;
		}
		if(tree.getData().val().startsWith("_")){
			//It's a proc. We need to recurse, but not check this "function"
			for(ParseTree child : tree.getChildren()){
				checkBreaks0(child, currentLoops, lastUnbreakable);
			}
			return;
		}
		Function func = ((CFunction)tree.getData()).getFunction();
		if(func.getClass().getAnnotation(nolinking.class) != null){
			// Don't link here
			return;
		}
		// We have special handling for procs and closures, and of course break and the loops. 
		// If any of these are here, we kick into special handling mode. Otherwise, we recurse.
		if(func instanceof DataHandling._break){
				// First grab the counter in the break function. If the break function doesn't
				// have any children, then 1 is implied. break() requires the argument to be
				// a CInt, so if it weren't, there would already have been a compile error, so
				// we can assume it will be a CInt.
				long breakCounter = 1;
				if(tree.getChildren().size() == 1){
					breakCounter = ((CInt)tree.getChildAt(0).getData()).getInt();
				}
				if(breakCounter > currentLoops){
					// Throw an exception, as this would break above a loop. Different error messages
					// are applied to different cases
					if(currentLoops == 0){
						throw new ConfigCompileException("The break() function can only break out of loops" + (lastUnbreakable == null ? "." :
								", but an attempt to break out of a " + lastUnbreakable + " was detected."), tree.getTarget());
					} else {
						throw new ConfigCompileException("Too many breaks"
								+ " detected. Check your loop nesting, and set the break count to an appropriate value.", tree.getTarget());
					}
				}
				return;
		} 
		if(func.getClass().getAnnotation(unbreakable.class) != null){		
				// Parse the children like normal, but reset the counter to 0.
				for(ParseTree child : tree.getChildren()){
					checkBreaks0(child, 0, func.getName());
				}
				return;
		}
		if(func.getClass().getAnnotation(breakable.class) != null){
				// Don't break yet, still recurse, but up our current loops counter.
				currentLoops++;
		}
		for(ParseTree child : tree.getChildren()){
			checkBreaks0(child, currentLoops, lastUnbreakable);
		}
	}
	
	/**
	 * Recurses down the tree and ensures that there are no dynamic labels. This has
	 * to finish completely after optimization, because the optimizer has no 
	 * good hook to know when optimization for a unit is fully completed, until
	 * ALL units are fully complete, so this happens separately after optimization,
	 * but as apart of the normal compile process.
	 * @param tree
	 * @throws ConfigCompileException 
	 */
	private static void checkLabels(ParseTree tree) throws ConfigCompileException {
//		for(ParseTree t : tree.getChildren()){
//			if(t.getData() instanceof CLabel){
//				if(((CLabel)t.getData()).cVal() instanceof IVariable){
//					throw new ConfigCompileException("Variables may not be used as labels", t.getTarget());
//				}
//			}
//			checkLabels(t);
//		}
	}
	
	/**
	 * Recurses down the tree and
	 * <ul><li>Links functions</li>
	 * <li>Checks function arguments</li></ul>
	 * This is a separate process from optimization, because optimization
	 * ignores any missing functions.
	 * @param tree 
	 */
	private static void link(ParseTree tree) throws ConfigCompileException{
		try {
			FunctionBase treeFunction = FunctionList.getFunction(tree.getData());
			if(treeFunction.getClass().getAnnotation(nolinking.class) != null){
				//Don't link children of a nolinking function.
				return;
			}
		} catch(ConfigCompileException ex){
			//This can happen if the treeFunction isn't a function, is a proc, etc,
			//but we don't care, we just want to continue.
		}
		for(ParseTree child : tree.getChildren()){
			if(child.getData() instanceof CFunction){
				FunctionBase f = null;
				if (!child.getData().val().matches("^_[^_].*")) {
					f = FunctionList.getFunction(child.getData());
					Integer[] numArgs = f.numArgs();
					if (!Arrays.asList(numArgs).contains(Integer.MAX_VALUE) && 
							!Arrays.asList(numArgs).contains(child.getChildren().size())) {
						throw new ConfigCompileException("Incorrect number of arguments passed to " 
								+ child.getData().val(), child.getData().getTarget());
					}
				}
				link(child);
			}
		}
	}
	
	private static final String __autoconcat__ = new Compiler.__autoconcat__().getName();

	/**
	 * Recurses down into the tree, attempting to optimize where possible. A few
	 * things have strong coupling, for information on these items, see the
	 * documentation included in the source.
	 *
	 * @param tree
	 * @return
	 */
	private static void optimize(ParseTree tree, Stack<List<Procedure>> procs) throws ConfigCompileException {
		if (tree.isOptimized()) {
			return; //Don't need to re-run this
		}
//		if (tree.getData() instanceof CIdentifier) {
//			optimize(((CIdentifier) tree.getData()).contained(), procs);
//			return;
//		}
		if (!(tree.getData() instanceof CFunction)) {
			//There's no way to optimize something that's not a function
			return;
		}
		//cc has to be inb4 other autoconcats, so sconcats on the lower level won't get run
		if (tree.getData().val().equals("cc")) {
			for (int i = 0; i < tree.getChildren().size(); i++) {
				ParseTree node = tree.getChildAt(i);
				if (node.getData().val().equals(__autoconcat__)) {
					Compiler.__autoconcat__ func = (Compiler.__autoconcat__) FunctionList.getFunction(node.getData());
					ParseTree tempNode = func.optimizeSpecial(node.getChildren(), false);
					tree.setData(tempNode.getData());
					tree.setChildren(tempNode.getChildren());
					optimize(tree, procs);
					return;
				}
			}
		}
		//If it is a proc definition, we need to go ahead and see if we can add it to the const proc stack
		if (tree.getData().val().equals("proc")) {
			procs.push(new ArrayList<Procedure>());
		}
		CFunction cFunction = (CFunction) tree.getData();
		Function func;
		try {
			func = (Function) FunctionList.getFunction(cFunction);
		} catch (ConfigCompileException e) {
			func = null;
		}
		if(func != null){
			if(func.getClass().getAnnotation(nolinking.class) != null){
				//It's an unlinking function, so we need to stop at this point
				return;
			}
		}
		if(cFunction instanceof CIdentifier){
			//Add the child to the identifier
			ParseTree c = ((CIdentifier)cFunction).contained();
			tree.addChild(c);
			c.getData().setWasIdentifier(true);
		}
		List<ParseTree> children = tree.getChildren();
		//Loop through the children, and if any of them are functions that are terminal, truncate.
		//To explain this further, consider the following:
		//For the code: concat(die(), msg('')), this diagram shows the abstract syntax tree:
		//         (concat)
		//        /        \
		//       /          \
		//     (die)       (msg)
		//By looking at the code, we can tell that msg() will never be called, because die() will run first,
		//and since it is a "terminal" function, any code after it will NEVER run. However, consider a more complex condition:
		// if(@input){ die() msg('1') } else { msg('2') msg('3') }
		//              if(@input)
		//        [true]/         \[false]
		//             /           \
		//         (sconcat)     (sconcat)
		//           /   \         /    \
		//          /     \       /      \
		//       (die) (msg[1])(msg[2]) (msg[3])
		//In this case, only msg('1') is guaranteed not to run, msg('2') and msg('3') will still run in some cases.
		//So, we can optimize out msg('1') in this case, which would cause the tree to become much simpler, therefore a worthwile optimization:
		//              if(@input)
		//        [true]/        \[false]
		//             /          \
		//          (die)      (sconcat)
		//                      /    \
		//                     /      \
		//                 (msg[2]) (msg[3])
		//We do have to be careful though, because of functions like if, which actually work like this:
		//if(@var){ die() } else { msg('') }
		//                (if)
		//              /  |  \
		//             /   |   \
		//          @var (die) (msg)
		//We can't git rid of the msg() here, because it is actually in another branch.
		//For the time being, we will simply say that if a function uses execs, it
		//is a branch (branches always use execs, though using execs doesn't strictly
		//mean you are a branch type function).
		
		for(int i = 0; i < children.size(); i++){
			ParseTree t = children.get(i);			
			if(t.getData() instanceof CFunction){
				if(t.getData().val().startsWith("_") || (func != null && func.useSpecialExec())){
					continue;
				}
				Function f = (Function)FunctionList.getFunction(t.getData());
				Set<OptimizationOption> options = NO_OPTIMIZATIONS;
				if(f instanceof Optimizable){
					options = ((Optimizable)f).optimizationOptions();
				}
				if(options.contains(OptimizationOption.TERMINAL)){
					if(children.size() > i + 1){
						//First, a compiler warning
						CHLog.GetLogger().Log(CHLog.Tags.COMPILER, LogLevel.WARNING, "Unreachable code. Consider removing this code.", children.get(i + 1).getTarget());
						//Now, truncate the children
						for(int j = children.size() - 1; j > i; j--){
							children.remove(j);							
						}
						break;
					}
				}
			}
		}
		boolean fullyStatic = true;
		boolean hasIVars = false;
		for (ParseTree node : children) {
			if (node.getData() instanceof CFunction) {
				optimize(node, procs);
			}

			if (node.getData().isDynamic() && !(node.getData() instanceof IVariable)) {
				fullyStatic = false;
			}
			if (node.getData() instanceof IVariable) {
				hasIVars = true;
			}
		}
		//In all cases, at this point, we are either unable to optimize, or we will
		//optimize, so set our optimized variable at this point.
		tree.setOptimized(true);
		
		if(func == null) {
			//It's a proc call. Let's see if we can optimize it
			Procedure p = null;
			//Did you know about this feature in java? I didn't until recently.
			//I break to the loop label, which makes it jump to the bottom of
			//that loop.
			loop:
			for (List<Procedure> proc : procs) {
				for (Procedure pp : proc) {
					if (pp.getName().equals(cFunction.val())) {
						p = pp;
						break loop;
					}
				}
			}
			if (p != null) {
				try {
					Construct c = DataHandling.proc.optimizeProcedure(p.getTarget(), p, children);
					if (c != null) {
						tree.setData(c);
						tree.removeChildren();
						return;
					}//else Nope, couldn't optimize.
				} catch (ConfigRuntimeException ex) {
					//Cool. Caught a runtime error at compile time :D
					throw new ConfigCompileException(ex);
				}
			} 
			//else this procedure isn't listed yet. Maybe a compiler error, maybe not, depends,
			//so we can't for sure say, but we do know we can't optimize this
			return;
		}
		if (tree.getData().val().equals("proc")) {
			//We just went out of scope, so we need to pop the layer of Procedures that
			//are internal to us
			procs.pop();
			//However, as a special function, we *might* be able to get a const proc out of this
			//Let's see.
			try {
				ParseTree root = new ParseTree(new CFunction(__autoconcat__, Target.UNKNOWN), fileOptions);
				Script fakeScript = Script.GenerateScript(root, "*");
				Environment env = null;
				try{
					env = Static.GenerateStandaloneEnvironment();
				} catch(IOException | DataSourceException | URISyntaxException | Profiles.InvalidProfileException e){
					//
				}
				Procedure myProc = DataHandling.proc.getProcedure(tree.getTarget(), env, fakeScript, children.toArray(new ParseTree[children.size()]));
				procs.peek().add(myProc); //Yep. So, we can move on with our lives now, and if it's used later, it could possibly be static.
			} catch (ConfigRuntimeException e) {
				//Well, they have an error in there somewhere
				throw new ConfigCompileException(e);
			} catch (NullPointerException e) {
				//Nope, can't optimize.
				return;
			}
		}

		//the compiler trick functions know how to deal with it specially, even if everything isn't
		//static, so do this first.
		String oldFunctionName = func.getName();
		Set<OptimizationOption> options = NO_OPTIMIZATIONS;
		if(func instanceof Optimizable){
			options = ((Optimizable)func).optimizationOptions();
		}
		if (options.contains(OptimizationOption.OPTIMIZE_DYNAMIC)) {
			ParseTree tempNode;
			try {
				tempNode = ((Optimizable)func).optimizeDynamic(tree.getData().getTarget(), tree.getChildren(), tree.getFileOptions());
			} catch (ConfigRuntimeException e) {
				//Turn it into a compile exception, then rethrow
				throw new ConfigCompileException(e);
			}
			if(tempNode == Optimizable.PULL_ME_UP){
				tempNode = tree.getChildAt(0);
			}
			if(tempNode == Optimizable.REMOVE_ME){
				tree.setData(new CFunction("p", Target.UNKNOWN));
				tree.removeChildren();
			} else if (tempNode != null) {
				tree.setData(tempNode.getData());
				tree.setOptimized(tempNode.isOptimized());
				tree.setChildren(tempNode.getChildren());
				tree.getData().setWasIdentifier(tempNode.getData().wasIdentifier());
				optimize(tree, procs);
				tree.setOptimized(true);
				//Some functions can actually make static the arguments, for instance, by pulling up a hardcoded
				//array, so if they have reversed this, make note of that now
				if(tempNode.hasBeenMadeStatic()){
					fullyStatic = true;
				}
			} //else it wasn't an optimization, but a compile check
		}
		if (!fullyStatic) {
			return;
		}
		//Otherwise, everything is static, or an IVariable and we can proceed.
		//Note since we could still have IVariables, we have to handle those
		//specially from here forward
		if (func.preResolveVariables() && hasIVars) {
			//Well, this function isn't equipped to deal with IVariables.
			return;
		}
		//It could have optimized by changing the name, in that case, we
		//don't want to run this now
		if (tree.getData().getValue().equals(oldFunctionName) && 
				(options.contains(OptimizationOption.OPTIMIZE_CONSTANT) || options.contains(OptimizationOption.CONSTANT_OFFLINE))) {
			Construct[] constructs = new Construct[tree.getChildren().size()];
			for (int i = 0; i < tree.getChildren().size(); i++) {
				constructs[i] = tree.getChildAt(i).getData();
			}
			try {
				Construct result;
				if(options.contains(OptimizationOption.CONSTANT_OFFLINE)){
					result = func.exec(tree.getData().getTarget(), null, constructs);
				} else {
					result = ((Optimizable)func).optimize(tree.getData().getTarget(), constructs);
				}

				//If the result is null, it was just a check, it can't optimize further.
				if (result != null) {
					result.setWasIdentifier(tree.getData().wasIdentifier());
					tree.setData(result);
					tree.removeChildren();
				}
			} catch (ConfigRuntimeException e) {
				//Turn this into a ConfigCompileException, then rethrow
				throw new ConfigCompileException(e);
			}
		}

		//It doesn't know how to optimize. Oh well.
	}
	
	/**
	 * Shorthand for lexing, compiling, and executing a script.
	 * @param script The textual script to execute
	 * @param file The file it was located in
	 * @param inPureMScript If it is pure MScript, or aliases
	 * @param env The execution environment
	 * @param done The MethodScriptComplete callback (may be null)
	 * @param s A script object (may be null)
	 * @param vars Any $vars (may be null)
	 * @return
	 * @throws ConfigCompileException 
	 */
	public static Construct execute(String script, File file, boolean inPureMScript, Environment env, MethodScriptComplete done, Script s, List<Variable> vars) throws ConfigCompileException{
		return execute(compile(lex(script, file, inPureMScript)), env, done, s, vars);
	}

	/**
	 * Executes a pre-compiled MethodScript, given the specified Script
	 * environment. Both done and script may be null, and if so, reasonable
	 * defaults will be provided. The value sent to done will also be returned,
	 * as a Construct, so this one function may be used synchronously also.
	 *
	 * @param root
	 * @param env
	 * @param done
	 * @param script
	 * @return 
	 */
	public static Construct execute(ParseTree root, Environment env, MethodScriptComplete done, Script script) {
		return execute(root, env, done, script, null);
	}

	/**
	 * Executes a pre-compiled MethodScript, given the specified Script
	 * environment, but also provides a method to set the constants in the
	 * script.
	 *
	 * @param root
	 * @param env
	 * @param done
	 * @param script
	 * @param vars
	 * @return
	 */
	public static Construct execute(ParseTree root, Environment env, MethodScriptComplete done, Script script, List<Variable> vars) {
		if (script == null) {
			script = new Script(null, null);
		}
		if (vars != null) {
			Map<String, Variable> varMap = new HashMap<>();
			for (Variable v : vars) {
				varMap.put(v.getName(), v);
			}
			for (Construct tempNode : root.getAllData()) {
				if (tempNode instanceof Variable) {
					Variable vv = varMap.get(((Variable) tempNode).getName());
					if(vv != null){
						((Variable) tempNode).setVal(vv.getDefault());
					} else {
						//The variable is unset. I'm not quite sure what cases would cause this
						((Variable) tempNode).setVal("");
					}
				}
			}
		}
		StringBuilder b = new StringBuilder();
		Construct returnable = null;
		for (ParseTree gg : root.getChildren()) {
			script.setLabel(env.getEnv(GlobalEnv.class).GetLabel());
			Construct retc = script.eval(gg, env);
			if (root.numberOfChildren() == 1) {
				returnable = retc;
			}
			String ret = retc instanceof CNull ? "null" : retc.val();
			if (ret != null && !ret.trim().isEmpty()) {
				b.append(ret).append(" ");
			}
		}
		if (done != null) {
			done.done(b.toString().trim());
		}
		if (returnable != null) {
			return returnable;
		}
		return Static.resolveConstruct(b.toString().trim(), Target.UNKNOWN);
	}

	public static void registerAutoIncludes(Environment env, Script s) {
		File root = env.getEnv(GlobalEnv.class).GetRootFolder();
		File auto_include = new File(root, "auto_include.ms");
		if (auto_include.exists()) {
			MethodScriptCompiler.execute(IncludeCache.get(auto_include, new Target(0, auto_include, 0)), env, null, s);
		}

		for (File f : Static.getAliasCore().autoIncludes) {
			MethodScriptCompiler.execute(IncludeCache.get(f, new Target(0, f, 0)), env, null, s);
		}
	}
}
