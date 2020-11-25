{ pkgs ? import <nixpkgs> {}
, stdenv ? pkgs.stdenv
, lib ? pkgs.lib
, makeWrapper ? pkgs.makeWrapper
, babashka ? pkgs.babashka
, doctl ? pkgs.doctl
, openssh ? pkgs.openssh
, nix ? pkgs.nix
, s3cmd ? pkgs.s3cmd
} :
stdenv.mkDerivation rec {
  name = "oceanix";
  src = ./.;
  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    mkdir -p $out/bin
    cp -R $src/* $out/
    makeWrapper $out/oceanix $out/bin/oceanix \
      --prefix PATH : ${babashka}/bin \
      --prefix PATH : ${doctl}/bin \
      --prefix PATH : ${openssh}/bin \
      --prefix PATH : ${nix}/bin \
      --prefix PATH : ${s3cmd}/bin \
  '';
}
