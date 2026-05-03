import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonUtil
{
    private JsonUtil()
    {
    }



    public static String stringify(Map<String, String> values)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        boolean first = true;

        for (Map.Entry<String, String> entry : values.entrySet())
        {
            if (!first)
            {
                builder.append(",");
            }

            builder.append("\"").append(escape(entry.getKey())).append("\":");
            builder.append("\"").append(escape(entry.getValue())).append("\"");
            first = false;
        }

        builder.append("}");
        return builder.toString();
    }



    public static Map<String, String> parse(String json)
    {
        Map<String, String> values = new LinkedHashMap<String, String>();

        if (json == null)
        {
            return values;
        }

        Parser parser = new Parser(json);
        return parser.parseObject();
    }



    private static String escape(String value)
    {
        if (value == null)
        {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);

            if (character == '\\' || character == '"')
            {
                builder.append('\\');
                builder.append(character);
            }
            else if (character == '\n')
            {
                builder.append("\\n");
            }
            else if (character == '\r')
            {
                builder.append("\\r");
            }
            else if (character == '\t')
            {
                builder.append("\\t");
            }
            else
            {
                builder.append(character);
            }
        }

        return builder.toString();
    }



    private static final class Parser
    {
        private final String json;
        private int position;



        private Parser(String json)
        {
            this.json = json.trim();
            this.position = 0;
        }



        private Map<String, String> parseObject()
        {
            Map<String, String> values = new LinkedHashMap<String, String>();
            skipWhitespace();

            if (!consume('{'))
            {
                return values;
            }

            skipWhitespace();

            while (position < json.length() && json.charAt(position) != '}')
            {
                String key = parseString();
                skipWhitespace();
                consume(':');
                skipWhitespace();
                String value = parseString();
                values.put(key, value);
                skipWhitespace();

                if (!consume(','))
                {
                    break;
                }

                skipWhitespace();
            }

            consume('}');
            return values;
        }



        private String parseString()
        {
            StringBuilder builder = new StringBuilder();

            if (!consume('"'))
            {
                return "";
            }

            while (position < json.length())
            {
                char character = json.charAt(position++);

                if (character == '"')
                {
                    break;
                }

                if (character == '\\' && position < json.length())
                {
                    char escaped = json.charAt(position++);

                    if (escaped == 'n')
                    {
                        builder.append('\n');
                    }
                    else if (escaped == 'r')
                    {
                        builder.append('\r');
                    }
                    else if (escaped == 't')
                    {
                        builder.append('\t');
                    }
                    else
                    {
                        builder.append(escaped);
                    }
                }
                else
                {
                    builder.append(character);
                }
            }

            return builder.toString();
        }



        private boolean consume(char expected)
        {
            if (position < json.length() && json.charAt(position) == expected)
            {
                position++;
                return true;
            }

            return false;
        }



        private void skipWhitespace()
        {
            while (position < json.length() && Character.isWhitespace(json.charAt(position)))
            {
                position++;
            }
        }
    }
}
