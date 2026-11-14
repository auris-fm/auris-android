# A triple-quoted string spans lines, and its closing line may carry a comment. Both cases
# failed before string state was carried across lines.

doc = """
body text
"""  # per @reviewer this comment follows the closing quotes DENY

value = 1  #per @spec no space after the hash DENY
