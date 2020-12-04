package org.podval.tools.cloudrun

import scala.annotation.tailrec
import scala.util.Random

object ThreeLetterWord {
  /**
   * Generate random 3-letter words.
   * Words are generated in a consonant-vowel-consonant order to be pronounceable.
   * A specific word matching this pattern has a 1/21*1/5*1/21 = 1/2205 chance
   * of being generated.
   *
   * The strings below contain bad words, swear words, and otherwise offensive
   * words as well as strings phonetically similar to offensive words and
   * misspellings of offensive words.
   *
   * This list is used as a blocklist to prevent potentially offensive words
   * from being generated and shown to users.
   *
   * Only 3-letter words matching the consonant-vowel-consonant pattern are included.
   *
   * Inspired by https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/name_generator.py
   */
  private val invalid: Set[String] = Set(
    "bah", "baj", "bal", "bam", "bar", "beh", "bew", "bez", "bic", "bin", "bod", "bok", "bol", "bon", "bow", "box",
    "bun", "bur", "bus", "cac", "cak", "caq", "cin", "coc", "cok", "con", "coq", "coz", "cuk", "cul", "cum", "cun",
    "cur", "dan", "daw", "day", "dem", "dev", "dic", "dik", "diq", "dix", "dom", "dot", "dud", "fag", "fak", "fan",
    "fap", "fas", "fek", "fel", "fez", "fis", "fob", "fok", "fot", "fuc", "fuk", "fuq", "fut", "fux", "gad", "gal",
    "gan", "gar", "gat", "gay", "gec", "gey", "gid", "gil", "giz", "gog", "gop", "got", "goy", "guc", "gud", "guk",
    "guq", "hag", "hah", "heh", "hen", "het", "hon", "hor", "huj", "hul", "hur", "hus", "jap", "jav", "jeb", "jew",
    "jit", "jiz", "job", "kac", "kak", "kan", "kaq", "kar", "kaw", "kef", "kel", "ken", "kep", "kik", "kir", "koc",
    "kok", "koq", "kor", "kox", "kuk", "kum", "kun", "kus", "kut", "kuz", "lam", "lan", "las", "lem", "let", "lic",
    "lid", "lik", "lil", "liq", "lol", "lop", "lox", "lud", "lul", "lun", "lus", "luz", "maj", "mal", "meg", "meh",
    "mes", "mic", "mik", "min", "miq", "mor", "muf", "mul", "mun", "mut", "nad", "nas", "nib", "nig", "nim", "noq",
    "nun", "nut", "pad", "pah", "pap", "par", "pat", "paz", "pek", "pel", "pes", "pik", "pis", "pix", "pod", "pom",
    "pot", "pug", "puk", "pum", "pus", "qab", "qij", "quz", "rac", "rak", "ral", "ran", "raq", "rev", "ris", "rit",
    "rot", "ruc", "sac", "sak", "saq", "sat", "sek", "ser", "set", "sex", "sey", "sik", "sob", "sod", "sol", "sot",
    "soy", "suc", "sud", "suk", "suq", "sut", "tal", "tay", "tem", "tin", "tit", "toc", "tog", "tok", "toq", "tos",
    "tun", "tup", "vag", "vaj", "wac", "wah", "wak", "waq", "war", "was", "wat", "wen", "wog", "wop", "xuy", "yal",
    "yid", "yor", "yuk", "zak", "zeb", "zig", "zov", "zut"
  )

  private val vowels: String = "aeiou"
  private val consonants: String = "bcdfghjklmnpqrstvwxyz"

  private def random(what: String): Char = what(Random.nextInt(what.length))

  def get(validate: Boolean): String = if (validate) getValid else get

  @tailrec
  def getValid: String = {
    val result: String = get
    if (!invalid.contains(result)) result else getValid
  }

  def get: String = Seq(random(consonants), random(vowels), random(consonants)).mkString
}
