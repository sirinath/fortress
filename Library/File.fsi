(*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************)

api File
import FileSupport.{...}

object FileReadStream(transient filename:String) extends { FileStream, ReadStream }
    getter fileName():String
    getter toString():String

    (** \VAR{eof} returns true if an end-of-file condition has been
        encountered on the stream. **)
    getter eof():Boolean

    (** \VAR{ready} returns true if there is currently input from the stream
        available to be consumed.
     **)
    getter ready():Boolean

    (** close the stream
     **)
    close():()

    (** \VAR{readLine} returns the next available line from the stream, discarding
        line termination characters.  Returns "" on eof.
     **)
    readLine():String

    (** Returns the next available character from the stream, or "" on eof.
     **)
    readChar():Char

    (** \VAR{read} returns the next \VAR{k} characters from the stream.  It will block
        until at least one character is available, and will then
        return as many characters as are ready.  Will return "" on end
        of file.  If k<=0 or absent a default value is chosen.
     **)
    read(k:ZZ32):String
    read():String

    (** All file generators yield file contents in parallel by
        default, with a natural ordering corresponding to the order
        data occurs in the underlying file.  The file is closed if all
        its contents have been read.

        These generators pull in multiple chunks of data (where a
        "chunk" is a line, a character, or a block) before it is
        processed.  The maximum number of chunks to pull in before
        processing is given by the last optional argument in every
        case; if it is <=0 or absent a default value is chosen.

        It is possible to read from a ReadStream before invoking any
        of the following methods.  Previously-read data is ignored,
        and only the remaining data is provided by the generator.

        At the moment it is illegal to read from a ReadStream once any
        of these methods has been called; we do not check for this
        condition.  However, the sequential versions of these
        generators may use label/exit or throw in order to escape from
        a loop, and the ReadStream will remain open and ready for
        reading.

        Once the ReadStream has been completely consumed it is closed.
     **)


    (** \VAR{lines} yields the lines found in the file a la \VAR{readLine}.
     **)
    lines(n:ZZ32):Generator[\String\]
    lines():Generator[\String\]

    (** \VAR{characters} yields the characters found in the file a la \VAR{readChar}.
     **)
    characters(n:ZZ32):Generator[\String\]
    characters():Generator[\String\]

    (** \VAR{chunks} returns chunks of characters found in the file, in the
        sense of \VAR{read}.  The first argument is equivalent to the
        argument \VAR{k} to read, the second (if present) is the number of
        chunks at a time.
     **)
    chunks(n:ZZ32,m:ZZ32):Generator[\String\]
    chunks(n:ZZ32): Generator[\String\]
    chunks(): Generator[\String\]
end

end
